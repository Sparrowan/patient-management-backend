package com.pm.billingservice.model;

/**
 * Lifecycle of an {@link IdempotencyRecord}. A record is claimed {@code IN_PROGRESS} on the first
 * request and flipped to {@code COMPLETED} once the response is captured — the two states the replay
 * logic branches on: {@code COMPLETED} → replay the stored response; {@code IN_PROGRESS} → a
 * duplicate is still being processed (in-flight) or the original crashed before completing.
 */
public enum IdempotencyStatus {
    IN_PROGRESS,
    COMPLETED
}
