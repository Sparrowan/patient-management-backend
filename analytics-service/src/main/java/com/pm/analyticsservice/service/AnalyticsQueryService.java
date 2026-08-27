package com.pm.analyticsservice.service;

import com.pm.analyticsservice.dto.DailyRegistrationView;
import com.pm.analyticsservice.dto.RegistrationSummaryView;
import java.time.LocalDate;
import java.util.List;

/** Read-only queries over the registrations read model. */
public interface AnalyticsQueryService {

    /** The registrations time-series between two dates, inclusive, oldest first. */
    List<DailyRegistrationView> registrationsBetween(LocalDate from, LocalDate to);

    /** All-time roll-up (total registered + number of active days). */
    RegistrationSummaryView summary();
}
