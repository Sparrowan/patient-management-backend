package com.pm.patientservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pm.events.PatientDeleted;
import com.pm.events.PatientRegistered;
import com.pm.patientservice.model.OutboxEvent;
import com.pm.patientservice.repository.OutboxEventRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * Unit tests for the outbox relay in isolation — repository and Kafka are mocked, so these assert
 * the publish-then-mark control flow and the failure handling only.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxRelay")
class OutboxRelayTest {

    private static final UUID PATIENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String TOPIC = "patient-events";

    @Mock private OutboxEventRepository outboxRepository;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private OutboxRelay relay;

    private OutboxEvent pendingEvent() throws Exception {
        String payload = objectMapper.writeValueAsString(new PatientRegisteredPayload(
                UUID.randomUUID().toString(), PATIENT_ID.toString(), "USD", 1_700_000_000_000L));
        return OutboxEvent.forPatientRegistered(PATIENT_ID, payload);
    }

    private OutboxEvent pendingDeletedEvent() throws Exception {
        String payload = objectMapper.writeValueAsString(new PatientDeletedPayload(
                UUID.randomUUID().toString(), PATIENT_ID.toString(), 1_700_000_000_000L));
        return OutboxEvent.forPatientDeleted(PATIENT_ID, payload);
    }

    @Test
    @DisplayName("publishes an unpublished event keyed by patientId and marks it published")
    void publishesAndMarks() throws Exception {
        OutboxEvent event = pendingEvent();
        when(outboxRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(event));
        CompletableFuture<SendResult<String, Object>> ok = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(eq(TOPIC), eq(PATIENT_ID.toString()), any(PatientRegistered.class)))
                .thenReturn(ok);

        relay.publishPending();

        assertThat(event.isPublished()).isTrue();
        assertThat(event.getAttempts()).isZero();
        verify(kafkaTemplate).send(eq(TOPIC), eq(PATIENT_ID.toString()), any(PatientRegistered.class));
    }

    @Test
    @DisplayName("routes a PatientDeleted row to the patient-events topic as a PatientDeleted record")
    void publishesDeletedEvent() throws Exception {
        OutboxEvent event = pendingDeletedEvent();
        when(outboxRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(event));
        CompletableFuture<SendResult<String, Object>> ok = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(eq("patient-events"), eq(PATIENT_ID.toString()), any(PatientDeleted.class)))
                .thenReturn(ok);

        relay.publishPending();

        assertThat(event.isPublished()).isTrue();
        verify(kafkaTemplate).send(eq("patient-events"), eq(PATIENT_ID.toString()), any(PatientDeleted.class));
    }

    @Test
    @DisplayName("on a broker failure, leaves the event unpublished and records the attempt")
    void recordsFailure() throws Exception {
        OutboxEvent event = pendingEvent();
        when(outboxRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(event));
        CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        when(kafkaTemplate.send(anyString(), anyString(), any(PatientRegistered.class))).thenReturn(failed);

        relay.publishPending();

        assertThat(event.isPublished()).isFalse();
        assertThat(event.getAttempts()).isEqualTo(1);
    }
}
