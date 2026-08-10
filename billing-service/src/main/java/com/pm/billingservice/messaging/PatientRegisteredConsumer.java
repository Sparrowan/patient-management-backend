package com.pm.billingservice.messaging;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.pm.billingservice.dto.OpenAccountRequestDTO;
import com.pm.billingservice.exception.AccountAlreadyExistsException;
import com.pm.billingservice.service.BillingAccountService;
import com.pm.events.PatientRegistered;

import lombok.RequiredArgsConstructor;

/**
 * Consumes {@link PatientRegistered} and opens the patient's billing account — the event-driven
 * replacement for the old synchronous gRPC trigger. Delegates to the same
 * {@link BillingAccountService#openAccount} the REST and gRPC layers use.
 *
 * <p><b>Idempotent consumer.</b> Delivery is at-least-once (outbox relay + Kafka), so the same
 * event can arrive more than once. Opening an account is naturally idempotent — it's keyed on
 * {@code patientId} and a duplicate throws {@link AccountAlreadyExistsException}, which we treat as
 * success (log + return, so the offset commits). It is deliberately <em>not</em> rethrown: a
 * duplicate is not a failure and must never be routed to the DLQ. Any other exception propagates to
 * the container error handler → retried, then dead-lettered to {@code patient.registered.DLT}.
 */
@Component
@RequiredArgsConstructor
public class PatientRegisteredConsumer {

    private static final Logger log = LoggerFactory.getLogger(PatientRegisteredConsumer.class);

    private final BillingAccountService accountService;

    @KafkaListener(topics = "patient.registered", groupId = "billing-service")
    public void onPatientRegistered(PatientRegistered event) {
        UUID patientId = UUID.fromString(event.getPatientId());
        try {
            accountService.openAccount(new OpenAccountRequestDTO(patientId, event.getCurrency()));
            log.info("Opened billing account for patient {} (event {})", patientId, event.getEventId());
        } catch (AccountAlreadyExistsException e) {
            log.info("Billing account already exists for patient {} — duplicate event {} ignored",
                    patientId, event.getEventId());
        }
    }
}
