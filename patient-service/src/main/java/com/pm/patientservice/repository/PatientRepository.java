package com.pm.patientservice.repository;

import com.pm.patientservice.model.Patient;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Data access for {@link Patient}. Spring Data JPA supplies the implementation at runtime.
 * The {@code existsBy...} finders back the email-uniqueness checks in the service.
 */
public interface PatientRepository extends JpaRepository<Patient, UUID> {

    boolean existsByEmail(String email);

    /** Email uniqueness on update, excluding the patient being updated. */
    boolean existsByEmailAndIdNot(String email, UUID id);

    /**
     * Loads a patient by id <b>including soft-deleted rows</b>. Uses a native query because
     * {@code @SQLRestriction("deleted_at is null")} filters deleted rows out of every JPQL query —
     * needed to restore a deleted patient (the deletion-rejected compensation).
     */
    @Query(value = "SELECT * FROM patients WHERE id = :id", nativeQuery = true)
    Optional<Patient> findByIdIncludingDeleted(UUID id);
}
