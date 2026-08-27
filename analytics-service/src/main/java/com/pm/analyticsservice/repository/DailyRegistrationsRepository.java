package com.pm.analyticsservice.repository;

import com.pm.analyticsservice.model.DailyRegistrations;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for the {@link DailyRegistrations} read model. Keyed by the date, so the projector's
 * "load the day's bucket" is a primary-key lookup. Range queries for the API arrive in the query bit.
 */
public interface DailyRegistrationsRepository extends JpaRepository<DailyRegistrations, LocalDate> {
}
