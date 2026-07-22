package com.pm.patientservice.repository;

import com.pm.patientservice.model.Patient;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for {@link Patient}. Spring Data JPA supplies the implementation at runtime,
 * including the CRUD methods used by the service layer (findAll, findById, save, ...).
 */
public interface PatientRepository extends JpaRepository<Patient, UUID> {
}
