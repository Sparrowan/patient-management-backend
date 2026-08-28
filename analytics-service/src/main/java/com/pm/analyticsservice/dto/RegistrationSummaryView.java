package com.pm.analyticsservice.dto;

/**
 * All-time roll-up of the registrations read model: the total registered and how many distinct days
 * saw at least one registration.
 */
public record RegistrationSummaryView(long totalRegistrations, long daysWithActivity) {
}
