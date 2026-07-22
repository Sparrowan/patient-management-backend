package com.pm.patientservice.exception;

import java.util.UUID;

/** Thrown when a patient lookup by id finds nothing. Mapped to HTTP 404 by the handler. */
public class PatientNotFoundException extends RuntimeException {

    public PatientNotFoundException(UUID id) {
        super("No patient found with id " + id);
    }
}
