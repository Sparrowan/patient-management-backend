package com.pm.billingservice.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A request to move {@code amount} from one account to another. {@code @Digits(fraction = 2)} keeps
 * money at the minor unit (never silently rounded). The {@code Idempotency-Key} travels as a header,
 * not in the body. The source and destination must differ (a self-transfer is a no-op / mistake).
 */
public record TransferRequestDTO(
        @NotNull UUID fromAccountId,
        @NotNull UUID toAccountId,
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @Size(max = 255) String description) {

    @AssertTrue(message = "fromAccountId and toAccountId must be different")
    private boolean isDistinctAccounts() {
        return fromAccountId == null || toAccountId == null || !fromAccountId.equals(toAccountId);
    }
}
