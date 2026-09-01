package com.pm.billingservice.exception;

/** Thrown when an {@code @Idempotent} endpoint is called without an Idempotency-Key. Mapped to 400. */
public class IdempotencyKeyMissingException extends RuntimeException {

    public IdempotencyKeyMissingException() {
        super("This endpoint requires an Idempotency-Key header.");
    }
}
