package com.pm.billingservice.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A request to pay {@code amount} out of a billing account to an external destination. The payout is
 * made in the source account's currency (not taken from the client). {@code @Digits(fraction = 2)}
 * keeps money at the minor unit (never silently rounded). The {@code Idempotency-Key} travels as a
 * header, not in the body.
 */
public record PayoutRequestDTO(
        @NotNull UUID sourceAccountId,
        @NotBlank @Size(max = 140) String destinationReference,
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @Size(max = 255) String description) {
}
