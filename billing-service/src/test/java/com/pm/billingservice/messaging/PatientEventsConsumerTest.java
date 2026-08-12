package com.pm.billingservice.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pm.billingservice.dto.OpenAccountRequestDTO;
import com.pm.billingservice.exception.AccountAlreadyExistsException;
import com.pm.billingservice.service.BillingAccountService;
import com.pm.events.PatientDeleted;
import com.pm.events.PatientRegistered;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the patient-events consumer in isolation. {@code PatientRegistered} opens the
 * account (idempotently); {@code PatientDeleted} is a fan-out no-op (deletion is handled by the
 * synchronous gRPC veto, which already closed the account).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PatientEventsConsumer")
class PatientEventsConsumerTest {

    private static final UUID PATIENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock private BillingAccountService accountService;
    @InjectMocks private PatientEventsConsumer consumer;

    private PatientRegistered registered() {
        return PatientRegistered.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setPatientId(PATIENT_ID.toString())
                .setCurrency("USD")
                .setOccurredAt(Instant.ofEpochMilli(1_700_000_000_000L))
                .build();
    }

    private PatientDeleted deleted() {
        return PatientDeleted.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setPatientId(PATIENT_ID.toString())
                .setOccurredAt(Instant.ofEpochMilli(1_700_000_000_000L))
                .build();
    }

    @Test
    @DisplayName("PatientRegistered opens a billing account for the patient")
    void opensAccount() {
        consumer.onPatientRegistered(registered());

        ArgumentCaptor<OpenAccountRequestDTO> captor = ArgumentCaptor.forClass(OpenAccountRequestDTO.class);
        verify(accountService).openAccount(captor.capture());
        assertThat(captor.getValue().patientId()).isEqualTo(PATIENT_ID);
        assertThat(captor.getValue().currency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("PatientRegistered swallows a duplicate (idempotent) — never rethrows, so no DLQ")
    void idempotentOnDuplicate() {
        when(accountService.openAccount(any())).thenThrow(new AccountAlreadyExistsException(PATIENT_ID));

        assertThatCode(() -> consumer.onPatientRegistered(registered())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("PatientDeleted is a fan-out no-op — billing takes no action (closed synchronously)")
    void patientDeletedIsNoOp() {
        consumer.onPatientDeleted(deleted());

        verifyNoInteractions(accountService);
    }
}
