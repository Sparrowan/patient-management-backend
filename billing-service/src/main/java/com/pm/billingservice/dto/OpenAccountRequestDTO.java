package com.pm.billingservice.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/**
 * Request to open a billing account. Validation lives here (not on the entity); the controller
 * enforces it with {@code @Valid}. Currency is an ISO-4217 alphabetic code (e.g. USD).
 */
public record OpenAccountRequestDTO(
        @NotNull UUID patientId,
        @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO-4217 code") String currency) {
}
