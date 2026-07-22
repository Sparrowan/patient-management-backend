package com.pm.patientservice.repository;

import com.pm.patientservice.model.Patient;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for {@link Patient}. Spring Data JPA supplies the implementation at runtime.
 * The {@code existsBy...} finders back the email-uniqueness checks in the service.
 */
public interface PatientRepository extends JpaRepository<Patient, UUID> {

    boolean existsByEmail(String email);

    /** Email uniqueness on update, excluding the patient being updated. */
    boolean existsByEmailAndIdNot(String email, UUID id);
}
