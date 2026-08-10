package com.pm.billingservice.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pm.billingservice.dto.OpenAccountRequestDTO;
import com.pm.billingservice.exception.AccountAlreadyExistsException;
import com.pm.billingservice.service.BillingAccountService;
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
 * Unit tests for the Kafka consumer in isolation — the service is mocked, so these assert the
 * event → DTO mapping and the idempotent handling of a duplicate delivery.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PatientRegisteredConsumer")
class PatientRegisteredConsumerTest {

    private static final UUID PATIENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock private BillingAccountService accountService;
    @InjectMocks private PatientRegisteredConsumer consumer;

    private PatientRegistered event() {
        return PatientRegistered.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setPatientId(PATIENT_ID.toString())
                .setCurrency("USD")
                .setOccurredAt(Instant.ofEpochMilli(1_700_000_000_000L))
                .build();
    }

    @Test
    @DisplayName("opens a billing account for the registered patient")
    void opensAccount() {
        consumer.onPatientRegistered(event());

        ArgumentCaptor<OpenAccountRequestDTO> captor = ArgumentCaptor.forClass(OpenAccountRequestDTO.class);
        verify(accountService).openAccount(captor.capture());
        assertThat(captor.getValue().patientId()).isEqualTo(PATIENT_ID);
        assertThat(captor.getValue().currency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("swallows a duplicate (idempotent) — never rethrows, so the offset commits and no DLQ")
    void idempotentOnDuplicate() {
        when(accountService.openAccount(any())).thenThrow(new AccountAlreadyExistsException(PATIENT_ID));

        assertThatCode(() -> consumer.onPatientRegistered(event())).doesNotThrowAnyException();
    }
}
