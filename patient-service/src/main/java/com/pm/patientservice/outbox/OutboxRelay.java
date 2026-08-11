package com.pm.patientservice.outbox;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pm.events.PatientDeleted;
import com.pm.events.PatientRegistered;
import com.pm.patientservice.model.OutboxEvent;
import com.pm.patientservice.repository.OutboxEventRepository;

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
 * <p>Polling (not CDC) is the deliberate starting point — simple, no extra infra. Debezium/CDC is
 * the scale evolution; multi-instance relay safety (SKIP LOCKED / ShedLock) is a follow-up. See ROADMAP.
 */
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final OutboxEventRepository outboxRepository;
    // Object value type: the relay publishes more than one Avro record type (PatientRegistered,
    // PatientDeleted). KafkaAvroSerializer serializes any SpecificRecord.
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${outbox.relay.poll-interval-ms:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outboxRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
        for (OutboxEvent event : batch) {
            try {
                publish(event);
                event.markPublished();
            } catch (Exception e) {
                event.recordFailedAttempt();
                log.warn("Outbox publish failed for event {} (attempt {}): {}",
                        event.getId(), event.getAttempts(), e.toString());
            }
        }
    }

    /**
     * Maps the stored JSON payload to the right Avro record (by event type) and sends it to the
     * single patient-events topic, keyed by {@code patientId} (the aggregate id). One topic + one
     * key ⇒ one partition ⇒ strict order, so a delete can never overtake its register (splitting
     * these across topics would break that — Kafka orders only within a topic-partition). Blocks on
     * the broker ack so a failure is caught here rather than escaping asynchronously.
     */
    private void publish(OutboxEvent event) throws Exception {
        Object record = switch (event.getEventType()) {
            case OutboxEvent.PATIENT_REGISTERED -> toRegistered(event.getPayload());
            case OutboxEvent.PATIENT_DELETED -> toDeleted(event.getPayload());
            default -> throw new IllegalStateException("Unknown outbox event type: " + event.getEventType());
        };
        kafkaTemplate.send(event.getTopic(), event.getAggregateId().toString(), record)
                .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private PatientRegistered toRegistered(String payloadJson) throws Exception {
        PatientRegisteredPayload p = objectMapper.readValue(payloadJson, PatientRegisteredPayload.class);
        return PatientRegistered.newBuilder()
                .setEventId(p.eventId())
                .setPatientId(p.patientId())
                .setCurrency(p.currency())
                .setOccurredAt(Instant.ofEpochMilli(p.occurredAt()))
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
