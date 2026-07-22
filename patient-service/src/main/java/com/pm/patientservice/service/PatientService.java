package com.pm.patientservice.service;

import com.pm.patientservice.dto.PagedResponse;
import com.pm.patientservice.dto.PatientRequestDTO;
import com.pm.patientservice.dto.PatientResponseDTO;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

/**
 * Business operations for patients. Controllers depend on this abstraction (DIP), not on the
 * implementation. Lookups that miss throw domain exceptions rather than returning null.
 */
public interface PatientService {

    /** Returns a page of patients. */
    PagedResponse<PatientResponseDTO> getPatients(Pageable pageable);

    /**
     * Returns a single patient.
     *
     * @throws com.pm.patientservice.exception.PatientNotFoundException if none exists
     */
    PatientResponseDTO getPatient(UUID id);

    /**
     * Registers a new patient.
     *
     * @throws com.pm.patientservice.exception.EmailAlreadyExistsException if the email is taken
     */
    PatientResponseDTO createPatient(PatientRequestDTO request);

    /**
     * Replaces an existing patient's details.
     *
     * @throws com.pm.patientservice.exception.PatientNotFoundException if none exists
     * @throws com.pm.patientservice.exception.EmailAlreadyExistsException if the email is taken
     *     by another patient
     */
    PatientResponseDTO updatePatient(UUID id, PatientRequestDTO request);

    /**
     * Deletes a patient.
     *
     * @throws com.pm.patientservice.exception.PatientNotFoundException if none exists
     */
    void deletePatient(UUID id);
}
