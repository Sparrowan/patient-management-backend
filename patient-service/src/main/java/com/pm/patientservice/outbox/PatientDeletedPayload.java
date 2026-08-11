package com.pm.patientservice.outbox;

/**
 * JSON shape stored in {@code outbox_events.payload} for a {@code PatientDeleted} event. Written by
 * the service on soft-delete and read back by the {@link OutboxRelay}, which maps it to the Avro
 * wire record. Identifiers only — never PHI.
 */
public record PatientDeletedPayload(String eventId, String patientId, long occurredAt) {
}
