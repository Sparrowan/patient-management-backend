package com.pm.analyticsservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

/**
 * One row per event this service has already applied — the consumer's idempotency ledger. Written in
 * the same transaction as the projection update it guards, so "did we apply this event?" and "apply
 * it" commit atomically. The {@code eventId} (the producer's stable per-event UUID) is the key.
 */
@Entity
@Table(name = "processed_events")
@Getter
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", length = 36)
    private String eventId;

    @Column(nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
    }

    private ProcessedEvent(String eventId) {
        this.eventId = eventId;
        this.processedAt = Instant.now();
    }

    public static ProcessedEvent of(String eventId) {
        return new ProcessedEvent(eventId);
    }
}
