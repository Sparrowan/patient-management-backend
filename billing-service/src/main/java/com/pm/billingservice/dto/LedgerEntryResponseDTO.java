package com.pm.billingservice.dto;

import com.pm.billingservice.model.EntryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A ledger entry as returned to clients — the record of one money movement, including the
 * {@code balanceAfter} it produced (so an idempotent replay returns a stable result).
 */
public record LedgerEntryResponseDTO(
        UUID id,
        UUID accountId,
        EntryType type,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String description,
        String idempotencyKey,
        Instant createdAt) {
}
