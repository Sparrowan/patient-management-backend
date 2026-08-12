package com.pm.patientservice.exception;

import java.util.UUID;

/**
 * Thrown when a patient cannot be deleted because billing vetoed it (the account still holds funds).
 * Mapped to HTTP 409 — the caller must settle the balance first.
 */
public class PatientDeletionConflictException extends RuntimeException {

    public PatientDeletionConflictException(UUID patientId, String reason) {
        super("Patient " + patientId + " cannot be deleted: " + reason);
    }
}
