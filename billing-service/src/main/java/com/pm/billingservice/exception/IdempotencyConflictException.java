package com.pm.billingservice.exception;

/**
 * Thrown when a request arrives with an Idempotency-Key that is still being processed by an earlier,
 * in-flight request. The client should retry after the first one finishes. Mapped to HTTP 409.
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("A request with this Idempotency-Key is already in progress. Retry once it completes.");
    }
}
