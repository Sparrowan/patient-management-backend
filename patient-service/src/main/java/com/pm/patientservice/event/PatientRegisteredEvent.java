package com.pm.patientservice.event;

import java.util.UUID;

/** Published after a patient is registered; triggers opening their billing account. */
public record PatientRegisteredEvent(UUID patientId, String currency) {
}
