package com.pm.patientservice.messaging;

import static org.mockito.Mockito.verify;

import com.pm.events.PatientDeletionRejected;
import com.pm.patientservice.service.PatientService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit test for the compensation consumer — verifies a rejection triggers the patient restore. */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillingEventsConsumer")
class BillingEventsConsumerTest {

    private static final UUID PATIENT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Mock private PatientService patientService;
    @InjectMocks private BillingEventsConsumer consumer;

    @Test
    @DisplayName("restores the patient when billing rejects the deletion")
    void restoresOnRejection() {
        PatientDeletionRejected event = PatientDeletionRejected.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setPatientId(PATIENT_ID.toString())
                .setReason("billing account has a non-zero balance")
                .setOccurredAt(Instant.ofEpochMilli(1_700_000_000_000L))
                .build();

        consumer.onPatientDeletionRejected(event);

        verify(patientService).restorePatient(PATIENT_ID, "billing account has a non-zero balance");
    }
}
