package com.pm.patientservice.outbox;

/**
 * JSON shape stored in the {@code outbox_events.payload} column for a {@code PatientRegistered}
 * event. Written by the service at registration and read back by the {@link OutboxRelay}, which
 * maps it to the Avro wire record. {@code occurredAt} is epoch-millis (stamped at registration, so
 * the timestamp is stable across relay retries). {@code actor} is the staff user (JWT sub) who
 * registered the patient, captured at registration so it's stable across retries — carried so the
 * billing consumer can audit the account it opens as that user (not {@code "system"}). Carries
 * identifiers only — never patient PHI.
 */
public record PatientRegisteredPayload(
        String eventId, String patientId, String currency, long occurredAt, String actor) {
}
