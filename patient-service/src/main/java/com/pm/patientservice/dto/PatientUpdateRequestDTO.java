package com.pm.patientservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Inbound update contract for a patient. Carries {@code version} — the value the client last
 * read — for optimistic concurrency control: if it no longer matches the stored version, the
 * record was changed by someone else and the update is rejected with 409.
 */
public record PatientUpdateRequestDTO(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 255) String address,
        @NotNull @Past LocalDate dateOfBirth,
        @NotNull Long version) {
}
