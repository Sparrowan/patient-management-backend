package com.pm.patientservice.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * API response contract for a patient. Decoupled from the {@link
 * com.pm.patientservice.model.Patient} entity so the persistence model can change without
 * breaking clients. Jackson serializes the UUID and LocalDate to ISO strings.
 *
 * <p>{@code version} is exposed so clients can send it back on update for optimistic
 * concurrency control (see {@link PatientUpdateRequestDTO}).
 */
public record PatientResponseDTO(
        UUID id,
        String name,
        String email,
        String address,
        LocalDate dateOfBirth,
        long version) {
}
