package com.pm.patientservice.messaging;

import java.util.UUID;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.pm.events.PatientDeletionRejected;
import com.pm.patientservice.service.PatientService;

import lombok.RequiredArgsConstructor;

/**
 * Consumes billing's compensating events. When billing rejects a patient deletion (the account
 * still holds funds), it restores the patient — the compensating action that completes the deletion
 * saga. {@code restorePatient} is idempotent, so a redelivery is harmless (no DLQ for duplicates).
 */
@Component
@RequiredArgsConstructor
public class BillingEventsConsumer {

    private final PatientService patientService;

    @KafkaListener(topics = "billing-events", groupId = "patient-service")
    public void onPatientDeletionRejected(PatientDeletionRejected event) {
        patientService.restorePatient(UUID.fromString(event.getPatientId()), event.getReason());
    }
}
