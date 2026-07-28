package com.pm.billingservice.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * A credit or debit request. {@code @Digits(fraction = 2)} rejects amounts more precise than the
 * minor unit — money is never silently rounded. The {@code Idempotency-Key} travels as a header,
 * not in the body.
 */
public record MoneyMovementRequestDTO(
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @Size(max = 255) String description) {
}
