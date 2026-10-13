package com.pm.patientservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pm.events.PatientDeleted;
import com.pm.events.PatientRegistered;
import com.pm.patientservice.model.OutboxEvent;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
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
 * Unit tests for the outbox relay in isolation — the claimer and Kafka are mocked, so these assert
 * the claim → publish → settle control flow and the failure handling only.
 *
 * <p>The relay no longer touches the repository or opens a transaction: settling is delegated to
 * {@link OutboxClaimer}, precisely so no transaction spans the Kafka send. These tests therefore
 * verify the <em>delegation</em>, and {@code OutboxClaimDoesNotBlockWritersTest} verifies the
 * locking behaviour that delegation buys, against a real database.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxRelay")
class OutboxRelayTest {

    private static final UUID PATIENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String TOPIC = "patient-events";

    @Mock private OutboxClaimer claimer;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    // Tracing collaborators: only exercised when a row carries a traceparent (see the restore test);
    // the other tests use untraced rows, so these mocks stay unused there.
    @Mock private Tracer tracer;
    @Mock private Propagator propagator;
    @InjectMocks private OutboxRelay relay;

    private OutboxEvent pendingEvent() throws Exception {
        String payload = objectMapper.writeValueAsString(new PatientRegisteredPayload(
                UUID.randomUUID().toString(), PATIENT_ID.toString(), "USD", 1_700_000_000_000L, "test-actor"));
        return OutboxEvent.forPatientRegistered(PATIENT_ID, payload, null);
    }

    private OutboxEvent pendingDeletedEvent() throws Exception {
        String payload = objectMapper.writeValueAsString(new PatientDeletedPayload(
                UUID.randomUUID().toString(), PATIENT_ID.toString(), 1_700_000_000_000L));
        return OutboxEvent.forPatientDeleted(PATIENT_ID, payload, null);
    }

    @Test
    @DisplayName("publishes an unpublished event keyed by patientId and marks it published")
    void publishesAndMarks() throws Exception {
        OutboxEvent event = pendingEvent();
        when(claimer.claimBatch(anyInt())).thenReturn(List.of(event));
        CompletableFuture<SendResult<String, Object>> ok = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(eq(TOPIC), eq(PATIENT_ID.toString()), any(PatientRegistered.class)))
                .thenReturn(ok);

        relay.publishPending();

        verify(kafkaTemplate).send(eq(TOPIC), eq(PATIENT_ID.toString()), any(PatientRegistered.class));
        verify(claimer).settlePublished(event.getId());
        verify(claimer, never()).settleFailed(any());
    }

    @Test
    @DisplayName("routes a PatientDeleted row to the patient-events topic as a PatientDeleted record")
    void publishesDeletedEvent() throws Exception {
        OutboxEvent event = pendingDeletedEvent();
        when(claimer.claimBatch(anyInt())).thenReturn(List.of(event));
        CompletableFuture<SendResult<String, Object>> ok = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(eq("patient-events"), eq(PATIENT_ID.toString()), any(PatientDeleted.class)))
                .thenReturn(ok);

        relay.publishPending();

        verify(kafkaTemplate).send(eq("patient-events"), eq(PATIENT_ID.toString()), any(PatientDeleted.class));
        verify(claimer).settlePublished(event.getId());
    }

    @Test
    @DisplayName("a row carrying a traceparent publishes inside the restored trace, then ends the span")
    void restoresStoredTraceContext() throws Exception {
        String payload = objectMapper.writeValueAsString(new PatientRegisteredPayload(
                UUID.randomUUID().toString(), PATIENT_ID.toString(), "USD", 1_700_000_000_000L, "actor"));
        OutboxEvent event = OutboxEvent.forPatientRegistered(PATIENT_ID, payload, "00-trace-span-01");
        when(claimer.claimBatch(anyInt())).thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), any(PatientRegistered.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        // Restore chain: propagator.extract(...).name(...).start() -> span; tracer.withSpan(span) -> scope.
        Span.Builder builder = mock(Span.Builder.class);
        Span span = mock(Span.class);
        when(propagator.extract(any(), any())).thenReturn(builder);
        when(builder.name(anyString())).thenReturn(builder);
        when(builder.start()).thenReturn(span);
        when(tracer.withSpan(span)).thenReturn(mock(Tracer.SpanInScope.class));

        relay.publishPending();

        verify(claimer).settlePublished(event.getId());
        verify(propagator).extract(any(), any()); // the stored context was restored
        verify(span).end();                       // and its span was closed after the send
    }

    @Test
    @DisplayName("on a broker failure, leaves the event unpublished and records the attempt")
    void recordsFailure() throws Exception {
        OutboxEvent event = pendingEvent();
        when(claimer.claimBatch(anyInt())).thenReturn(List.of(event));
        CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        when(kafkaTemplate.send(anyString(), anyString(), any(PatientRegistered.class))).thenReturn(failed);

        relay.publishPending();

        // A failed send must settle as a failure -- which releases the claim, so the next tick
        // retries immediately instead of waiting out the lease.
        verify(claimer).settleFailed(event.getId());
        verify(claimer, never()).settlePublished(any());
    }
}
