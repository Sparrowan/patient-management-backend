package com.pm.billingservice.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pm.billingservice.dto.BillingAccountResponseDTO;
import com.pm.billingservice.dto.OpenAccountRequestDTO;
import com.pm.billingservice.exception.AccountAlreadyExistsException;
import com.pm.billingservice.exception.BillingAccountNotFoundException;
import com.pm.billingservice.model.AccountStatus;
import com.pm.billingservice.service.BillingAccountService;
import com.pm.events.PatientDeleted;
import com.pm.events.PatientRegistered;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the patient-events consumer in isolation — the service is mocked. Covers both
 * {@code @KafkaHandler} branches (register → open, delete → deactivate) and their idempotent paths.
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

    @Nested
    @DisplayName("PatientRegistered")
    class Registered {

        @Test
        @DisplayName("opens a billing account for the registered patient")
        void opensAccount() {
            consumer.onPatientRegistered(registered());

            ArgumentCaptor<OpenAccountRequestDTO> captor = ArgumentCaptor.forClass(OpenAccountRequestDTO.class);
            verify(accountService).openAccount(captor.capture());
            org.assertj.core.api.Assertions.assertThat(captor.getValue().patientId()).isEqualTo(PATIENT_ID);
            org.assertj.core.api.Assertions.assertThat(captor.getValue().currency()).isEqualTo("USD");
        }

        @Test
        @DisplayName("swallows a duplicate (idempotent) — never rethrows, so no DLQ")
        void idempotentOnDuplicate() {
            when(accountService.openAccount(org.mockito.ArgumentMatchers.any()))
                    .thenThrow(new AccountAlreadyExistsException(PATIENT_ID));

            assertThatCode(() -> consumer.onPatientRegistered(registered())).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("PatientDeleted")
    class Deleted {

        @Test
        @DisplayName("deactivates the billing account for the deleted patient")
        void deactivatesAccount() {
            when(accountService.deactivateForPatient(PATIENT_ID)).thenReturn(new BillingAccountResponseDTO(
                    UUID.randomUUID(), PATIENT_ID, AccountStatus.CLOSED, BigDecimal.ZERO, "USD", 1L));

            consumer.onPatientDeleted(deleted());

            verify(accountService).deactivateForPatient(PATIENT_ID);
        }

        @Test
        @DisplayName("swallows 'no account' — nothing to deactivate, never rethrows (no DLQ)")
        void noAccountIsIdempotentSuccess() {
            when(accountService.deactivateForPatient(PATIENT_ID))
                    .thenThrow(new BillingAccountNotFoundException(PATIENT_ID));

            assertThatCode(() -> consumer.onPatientDeleted(deleted())).doesNotThrowAnyException();
        }
    }
}
