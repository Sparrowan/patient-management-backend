package com.pm.billingservice.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.pm.billingservice.model.TransferStatus;

/** A transfer as returned to clients. {@code createdAt} maps from {@code BaseEntity}. */
public record TransferResponseDTO(
        UUID id,
        UUID fromAccountId,
        UUID toAccountId,
        BigDecimal amount,
        String currency,
        TransferStatus status,
        String description,
        String idempotencyKey,
        Instant createdAt) {
}
