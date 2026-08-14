package com.pm.authservice.exception;

/** Thrown when registering a username that is already taken. Mapped to HTTP 409. */
public class UsernameAlreadyExistsException extends RuntimeException {

    public UsernameAlreadyExistsException(String username) {
        super("Username already in use: " + username);
    }
}
