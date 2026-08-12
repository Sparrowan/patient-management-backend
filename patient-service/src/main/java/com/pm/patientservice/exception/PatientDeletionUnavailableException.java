package com.pm.patientservice.exception;

import java.util.UUID;

/**
 * Thrown when the deletion precondition can't be verified because billing is unreachable. Mapped to
 * HTTP 503 — deletion is blocked (fail safe) until billing is available to confirm the account.
 */
public class PatientDeletionUnavailableException extends RuntimeException {

    public PatientDeletionUnavailableException(UUID patientId) {
        super("Cannot delete patient " + patientId + " right now: billing is unavailable to verify the account");
    }
}
