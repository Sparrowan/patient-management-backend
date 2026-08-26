package com.pm.billingservice.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.pm.billingservice.model.PayoutStatus;

/** A payout as returned to clients. {@code createdAt} maps from {@code BaseEntity}. */
public record PayoutResponseDTO(
        UUID id,
        UUID sourceAccountId,
        String destinationReference,
        BigDecimal amount,
        String currency,
        PayoutStatus status,
        String description,
        String idempotencyKey,
        Instant createdAt) {
}
