package com.pm.analyticsservice.dto;

/** The live count of currently-active (registered, not deleted) patients. */
public record ActivePatientsView(long activePatients) {
}
