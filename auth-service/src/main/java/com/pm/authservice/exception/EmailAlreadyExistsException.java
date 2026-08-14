package com.pm.authservice.exception;

/** Thrown when registering an email that is already taken. Mapped to HTTP 409. */
public class EmailAlreadyExistsException extends RuntimeException {

    public EmailAlreadyExistsException(String email) {
        super("Email already in use: " + email);
    }
}
