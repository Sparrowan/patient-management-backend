package com.pm.billingservice.dto;

import com.pm.billingservice.model.AccountStatus;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * API response for a billing account. Decoupled from the entity. {@code balance} is serialized as
 * a JSON number from {@link BigDecimal} (never a floating-point type). {@code version} is exposed
 * for optimistic-concurrency updates.
 */
public record BillingAccountResponseDTO(
        UUID id,
        UUID patientId,
        AccountStatus status,
        BigDecimal balance,
        String currency,
        long version) {
}
