package com.pm.patientservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

/**
 * A transactional-outbox row. Written in the same DB transaction as the business change it
 * describes, so the decision to publish an event is atomic with the state change — it can never be
 * lost to a mid-flight crash (unlike a direct "save then publish" dual write). The
 * {@code OutboxRelay} later ships unpublished rows to Kafka and stamps {@link #publishedAt}.
 *
 * <p>Deliberately does <em>not</em> extend {@link BaseEntity}: it needs no soft-delete, and no
 * optimistic {@code @Version} — the relay is the only writer after insert. {@code createdAt} is
 * stamped by the factory (not JPA auditing) so it's set the instant the intent is recorded.
 *
 * <p>Rich model: created via {@link #forPatientRegistered} and mutated only through
 * {@link #markPublished()} / {@link #recordFailedAttempt()} — no public setters.
 */
@Entity
@Table(name = "outbox_events")
@Getter
public class OutboxEvent {

    private static final String PATIENT_AGGREGATE = "Patient";
    private static final String PATIENT_REGISTERED = "PatientRegistered";
    private static final String PATIENT_REGISTERED_TOPIC = "patient.registered";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 64)
    private String aggregateType;

    @Column(nullable = false)
    private UUID aggregateId;

    @Column(nullable = false, length = 64)
    private String eventType;

    @Column(nullable = false, length = 128)
    private String topic;

    @Column(nullable = false, length = 2048)
    private String payload;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** Null until the relay confirms the broker accepted the record. */
    @Column
    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    /** Required by JPA. Use {@link #forPatientRegistered}. */
    protected OutboxEvent() {
    }

    private OutboxEvent(String aggregateType, UUID aggregateId, String eventType, String topic, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.payload = payload;
        this.createdAt = Instant.now();
        this.attempts = 0;
    }

    /** Records the intent to publish a {@code PatientRegistered} event for the given patient. */
    public static OutboxEvent forPatientRegistered(UUID patientId, String payloadJson) {
        return new OutboxEvent(
                PATIENT_AGGREGATE, patientId, PATIENT_REGISTERED, PATIENT_REGISTERED_TOPIC, payloadJson);
    }

    /** Marks the event as delivered to the broker (relay stamps this on a successful send). */
    public void markPublished() {
        this.publishedAt = Instant.now();
    }

    /** Bumps the failed-attempt counter so a poison row is visible/alertable rather than silent. */
    public void recordFailedAttempt() {
        this.attempts++;
    }

    public boolean isPublished() {
        return publishedAt != null;
    }
}
