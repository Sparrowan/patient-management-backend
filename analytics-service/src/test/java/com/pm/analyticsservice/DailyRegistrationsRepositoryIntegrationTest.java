package com.pm.analyticsservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.pm.analyticsservice.model.DailyRegistrations;
import com.pm.analyticsservice.repository.DailyRegistrationsRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves the read model is wired end-to-end: the Flyway migration created the table, Hibernate
 * validated the entity against it (the context wouldn't boot otherwise), and a bucket round-trips.
 */
@DisplayName("DailyRegistrations read model (integration)")
class DailyRegistrationsRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DailyRegistrationsRepository repository;

    @BeforeEach
    void reset() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("persists a daily bucket and folds in registrations")
    void roundTrips() {
        LocalDate day = LocalDate.of(2026, 8, 27);
        DailyRegistrations bucket = DailyRegistrations.startOn(day);
        bucket.recordOne();
        bucket.recordOne();
        repository.save(bucket);

        DailyRegistrations loaded = repository.findById(day).orElseThrow();
        assertThat(loaded.getRegistrations()).isEqualTo(2);
    }
}
