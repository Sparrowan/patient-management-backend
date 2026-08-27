package com.pm.analyticsservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.pm.analyticsservice.dto.DailyRegistrationView;
import com.pm.analyticsservice.dto.RegistrationSummaryView;
import com.pm.analyticsservice.model.DailyRegistrations;
import com.pm.analyticsservice.repository.DailyRegistrationsRepository;
import com.pm.analyticsservice.service.AnalyticsQueryService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Exercises the read-side queries against real MariaDB — in particular the JPQL constructor
 * expressions that project straight into the response records.
 */
@DisplayName("Analytics query service (integration)")
class AnalyticsQueryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AnalyticsQueryService analyticsQueryService;
    @Autowired
    private DailyRegistrationsRepository repository;

    @BeforeEach
    void reset() {
        repository.deleteAll();
    }

    private void seed(LocalDate day, long count) {
        DailyRegistrations bucket = DailyRegistrations.startOn(day);
        for (long i = 0; i < count; i++) {
            bucket.recordOne();
        }
        repository.save(bucket);
    }

    @Test
    @DisplayName("range query returns the series within [from, to], oldest first")
    void rangeQuery() {
        seed(LocalDate.of(2026, 8, 20), 2);
        seed(LocalDate.of(2026, 8, 22), 5);
        seed(LocalDate.of(2026, 8, 25), 1); // outside the range

        List<DailyRegistrationView> series =
                analyticsQueryService.registrationsBetween(LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 23));

        assertThat(series).extracting(DailyRegistrationView::date)
                .containsExactly(LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 22));
        assertThat(series).extracting(DailyRegistrationView::registrations)
                .containsExactly(2L, 5L);
    }

    @Test
    @DisplayName("summary totals registrations across all active days")
    void summaryTotals() {
        seed(LocalDate.of(2026, 8, 20), 2);
        seed(LocalDate.of(2026, 8, 22), 5);

        RegistrationSummaryView summary = analyticsQueryService.summary();

        assertThat(summary.totalRegistrations()).isEqualTo(7);
        assertThat(summary.daysWithActivity()).isEqualTo(2);
    }
}
