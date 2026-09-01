package com.pm.billingservice.exception;

/**
 * Thrown when an Idempotency-Key is reused for a request whose payload differs from the original
 * (the fingerprints don't match) — a client bug: one key must mean one request. Mapped to HTTP 422.
 */
public class IdempotencyKeyReuseException extends RuntimeException {

    public IdempotencyKeyReuseException() {
        super("This Idempotency-Key was already used for a different request. "
                + "Use a new key for a new request.");
    }
}
