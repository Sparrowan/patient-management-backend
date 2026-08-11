package com.pm.billingservice.messaging;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.pm.events.PatientDeletionRejected;

import lombok.RequiredArgsConstructor;

/**
 * Publishes billing's outbound (compensating) events to the {@code billing-events} topic, keyed by
 * {@code patientId} so a patient's events stay ordered.
 */
@Component
@RequiredArgsConstructor
public class BillingEventsPublisher {

    private static final String TOPIC = "billing-events";
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final KafkaTemplate<String, Object> billingEventsKafkaTemplate;

    /**
     * Emits the compensating {@code PatientDeletionRejected}. Blocks on the broker ack so a failure
     * propagates to the caller (the consumer's offset won't commit → the inbound event redelivers →
     * retry); the downstream restore is idempotent, so an occasional duplicate is harmless.
     */
    public void publishDeletionRejected(UUID patientId, String reason) {
        PatientDeletionRejected event = PatientDeletionRejected.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setPatientId(patientId.toString())
                .setReason(reason)
                .setOccurredAt(Instant.now())
                .build();
        try {
            billingEventsKafkaTemplate.send(TOPIC, patientId.toString(), event)
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted publishing PatientDeletionRejected", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish PatientDeletionRejected", e);
        }
    }
}
