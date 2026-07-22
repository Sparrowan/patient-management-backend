package com.pm.patientservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Inbound create/update contract for a patient. Bean Validation lives here (not on the
 * entity); the controller enforces it with {@code @Valid}. {@code registeredDate} is
 * deliberately absent — it is server-set, never accepted from the client.
 */
public record PatientRequestDTO(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 255) String address,
        @NotNull @Past LocalDate dateOfBirth) {
}
