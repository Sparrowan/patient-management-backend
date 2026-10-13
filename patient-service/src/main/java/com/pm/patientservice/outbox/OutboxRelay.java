package com.pm.patientservice.outbox;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pm.events.PatientDeleted;
import com.pm.events.PatientRegistered;
import com.pm.patientservice.model.OutboxEvent;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.Map;

import lombok.RequiredArgsConstructor;

/**
 * Polling relay for the transactional outbox. Each tick it reads a batch of unpublished rows,
 * publishes each to Kafka (as Avro, via Schema Registry), and stamps {@code publishedAt}.
 *
 * <p><b>Delivery semantics:</b> at-least-once. If a send succeeds but the transaction fails to
 * commit the {@code publishedAt} stamp, the row is re-published next tick — which is safe because
 * the consumer is idempotent (opening an account is keyed on {@code patientId}). At-least-once +
 * idempotent consumer = effectively exactly-once.
 *
 * <p>A per-row {@code try/catch} keeps one bad row from rolling back the rest of the batch; a
 * failed send just bumps {@code attempts} and leaves the row unpublished for the next tick.
 *
 * <p><b>Multi-instance safe, without holding a lock across the network.</b> A batch is claimed by
 * stamping a lease on it ({@link OutboxClaimer#claimBatch}) in a short, {@code READ_COMMITTED}
 * transaction; the Kafka send then happens with <em>no transaction open</em>; each row is finally
 * settled in its own short transaction. Two relays racing cannot both win a row — the claiming
 * {@code UPDATE} is atomic, so the loser's predicate no longer matches.
 *
 * <p><b>This replaced a real defect, worth recording so it is not reintroduced.</b> The claim used to
 * be a {@code SELECT … FOR UPDATE SKIP LOCKED} held for the whole transaction, Kafka send included —
 * because the lock <em>was</em> the claim, it had to outlive the send. But {@code SKIP LOCKED} only
 * spares other readers taking the same lock, and under MariaDB's default REPEATABLE READ the
 * {@code published_at IS NULL} range scan also takes <b>gap locks</b>, which block <em>inserts</em>
 * into that range. Registering a patient inserts into exactly that range. So a slow or unreachable
 * broker made each send burn its timeout holding those gaps, and {@code POST /patients} started
 * failing with {@code Lock wait timeout exceeded} — measured at ~6s to block, then time out. An
 * outbox exists precisely so the business transaction does not depend on the broker; that version
 * inverted it.
 *
 * <p>Delivery remains <b>at-least-once</b>, and the failure windows are explicit: a crash between
 * send and settle re-publishes that row when the lease expires, which consumers absorb because they
 * are idempotent on {@code eventId}.
 *
 * <p>Polling (not CDC) is the deliberate starting point — simple, no extra infra. Debezium/CDC is the
 * scale evolution. See ROADMAP.
 */
@Component
@ConditionalOnProperty(prefix = "outbox.relay", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long SEND_TIMEOUT_SECONDS = 5;
    private static final int BATCH_SIZE = 100;

    private final OutboxClaimer claimer;
    // Object value type: the relay publishes more than one Avro record type (PatientRegistered,
    // PatientDeleted). KafkaAvroSerializer serializes any SpecificRecord. Injected by name (there
    // are other KafkaTemplate<String,Object> beans for the consumer DLQ).
    private final KafkaTemplate<String, Object> patientEventsKafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final Propagator propagator;

    /**
     * Claim, publish, settle — deliberately <b>three</b> steps, with no transaction open across the
     * Kafka send.
     *
     * <p>Note what is <em>not</em> transactional here: this method. Each step below has its own short
     * transaction, and the send sits between them with none. That is the entire point — see the
     * class javadoc for the lock-wait failure this replaced.
     */
    @Scheduled(fixedDelayString = "${outbox.relay.poll-interval-ms:1000}")
    public void publishPending() {
        List<OutboxEvent> batch = claimer.claimBatch(BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }
        for (OutboxEvent event : batch) {
            try {
                publishInOriginatingTrace(event);
                claimer.settlePublished(event.getId());
            } catch (Exception e) {
                // Release the claim immediately rather than letting the lease run down: a transient
                // broker error should be retried on the next tick, not held for the lease duration.
                int attempts = claimer.settleFailed(event.getId());
                log.warn("Outbox publish failed for event {} (attempt {}): {}",
                        event.getId(), attempts, e.toString());
            }
        }
    }

    /**
     * Publishes within the trace that originally wrote the row. The stored W3C traceparent is
     * restored as the current span, so the KafkaTemplate producer span (and the downstream consumer)
     * join the originating request's trace instead of this relay's scheduled-task trace — the outbox
     * decouples the two threads, and this re-links them. No stored context (rows written before the
     * feature, or outside any trace) → publish as-is under the relay's own context.
     */
    private void publishInOriginatingTrace(OutboxEvent event) throws Exception {
        String traceParent = event.getTraceParent();
        if (traceParent == null || traceParent.isBlank()) {
            publish(event);
            return;
        }
        Span span = propagator.extract(Map.of("traceparent", traceParent), (carrier, key) -> carrier.get(key))
                .name("outbox-publish")
                .start();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            publish(event);
        } finally {
            span.end();
        }
    }

    /**
     * Maps the stored JSON payload to the right Avro record (by event type) and sends it to the
     * single patient-events topic, keyed by {@code patientId} (the aggregate id). Kafka orders only
     * within a topic-partition, and the default partitioner sends a given key to the same partition
     * — so <em>same key ⇒ same partition ⇒ strict per-patient order</em>, and a delete can never
     * overtake its register. Note what the guarantee does and does not rest on: it comes from the
     * keying, <b>not</b> from the topic having a single partition, which is why the topic can be
     * widened to parallelise consumers without weakening it. Splitting these event types across
     * separate topics would break it. Blocks on the broker ack so a failure is caught here rather
     * than escaping asynchronously.
     */
    private void publish(OutboxEvent event) throws Exception {
        Object record = switch (event.getEventType()) {
            case OutboxEvent.PATIENT_REGISTERED -> toRegistered(event.getPayload());
            case OutboxEvent.PATIENT_DELETED -> toDeleted(event.getPayload());
            default -> throw new IllegalStateException("Unknown outbox event type: " + event.getEventType());
        };
        patientEventsKafkaTemplate.send(event.getTopic(), event.getAggregateId().toString(), record)
                .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private PatientRegistered toRegistered(String payloadJson) throws Exception {
        PatientRegisteredPayload p = objectMapper.readValue(payloadJson, PatientRegisteredPayload.class);
        return PatientRegistered.newBuilder()
                .setEventId(p.eventId())
                .setPatientId(p.patientId())
                .setCurrency(p.currency())
                .setOccurredAt(Instant.ofEpochMilli(p.occurredAt()))
                .setActor(p.actor())
                .build();
    }

    private PatientDeleted toDeleted(String payloadJson) throws Exception {
        PatientDeletedPayload p = objectMapper.readValue(payloadJson, PatientDeletedPayload.class);
        return PatientDeleted.newBuilder()
                .setEventId(p.eventId())
                .setPatientId(p.patientId())
                .setOccurredAt(Instant.ofEpochMilli(p.occurredAt()))
                .build();
    }
}
