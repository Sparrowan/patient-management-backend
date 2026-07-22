package com.pm.patientservice.service;

import com.pm.patientservice.dto.PatientResponseDTO;
import java.util.List;

/**
 * Business operations for patients. Controllers depend on this abstraction (DIP), not on the
 * implementation.
 */
public interface PatientService {

    /**
     * Returns all patients.
     *
     * @return every patient as a response DTO (empty list if none)
     */
    List<PatientResponseDTO> getPatients();
}
