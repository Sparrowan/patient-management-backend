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
    /** Event-type discriminators — the relay switches on these to build the right Avro record. */
    public static final String PATIENT_REGISTERED = "PatientRegistered";
    public static final String PATIENT_DELETED = "PatientDeleted";
    // ONE topic for all patient lifecycle events, keyed by patientId. Kafka guarantees order only
    // within a topic-partition, so splitting register/delete across topics would let a delete
    // overtake its register (verified — it does). One ordered stream per patient prevents that.
    private static final String PATIENT_EVENTS_TOPIC = "patient-events";

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

    /**
     * W3C traceparent of the request that wrote this row (e.g. {@code 00-<trace>-<span>-<flags>}),
     * captured so the relay can continue that trace when it publishes later on its own scheduled
     * thread. Null = no active trace at write time; the relay then publishes untraced.
     */
    @Column(length = 64)
    private String traceParent;

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

    private OutboxEvent(String aggregateType, UUID aggregateId, String eventType, String topic,
            String payload, String traceParent) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.payload = payload;
        this.traceParent = traceParent;
        this.createdAt = Instant.now();
        this.attempts = 0;
    }

    /**
     * Records the intent to publish a {@code PatientRegistered} event for the given patient.
     * {@code traceParent} is the caller's W3C trace context (may be null) so the eventual publish
     * continues the originating trace.
     */
    public static OutboxEvent forPatientRegistered(UUID patientId, String payloadJson, String traceParent) {
        return new OutboxEvent(
                PATIENT_AGGREGATE, patientId, PATIENT_REGISTERED, PATIENT_EVENTS_TOPIC, payloadJson, traceParent);
    }

    /** Records the intent to publish a {@code PatientDeleted} event for the given patient. */
    public static OutboxEvent forPatientDeleted(UUID patientId, String payloadJson, String traceParent) {
        return new OutboxEvent(
                PATIENT_AGGREGATE, patientId, PATIENT_DELETED, PATIENT_EVENTS_TOPIC, payloadJson, traceParent);
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
