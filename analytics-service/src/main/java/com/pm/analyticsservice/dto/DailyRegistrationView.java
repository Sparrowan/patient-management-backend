package com.pm.analyticsservice.dto;

import java.time.LocalDate;

/** One point in the registrations time-series: how many patients registered on {@code date}. */
public record DailyRegistrationView(LocalDate date, long registrations) {
}
