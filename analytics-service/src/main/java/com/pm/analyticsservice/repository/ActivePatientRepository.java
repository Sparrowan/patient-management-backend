package com.pm.analyticsservice.repository;

import com.pm.analyticsservice.model.ActivePatient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for the {@link ActivePatient} set. {@code count()} is the live active-patient gauge. */
public interface ActivePatientRepository extends JpaRepository<ActivePatient, String> {

    /**
     * Idempotent removal: deletes the row if present, a no-op if not. Preferred over
     * {@code deleteById}, which throws {@code EmptyResultDataAccessException} on a missing id — that
     * would make a redelivered/duplicate deletion fail instead of being harmlessly ignored.
     */
    @Modifying
    @Query("delete from ActivePatient a where a.patientId = :id")
    void removeById(@Param("id") String id);
}
