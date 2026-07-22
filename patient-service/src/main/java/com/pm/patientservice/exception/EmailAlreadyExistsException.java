package com.pm.patientservice.exception;

/** Thrown when creating/updating a patient with an email already in use. Mapped to HTTP 409. */
public class EmailAlreadyExistsException extends RuntimeException {

    public EmailAlreadyExistsException(String email) {
        super("Email already in use: " + email);
    }
}
