package com.pm.billingservice.messaging;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.pm.billingservice.dto.BillingAccountResponseDTO;
import com.pm.billingservice.dto.OpenAccountRequestDTO;
import com.pm.billingservice.exception.AccountAlreadyExistsException;
import com.pm.billingservice.exception.AccountHasBalanceException;
import com.pm.billingservice.exception.BillingAccountNotFoundException;
import com.pm.billingservice.service.BillingAccountService;
import com.pm.events.PatientDeleted;
import com.pm.events.PatientRegistered;

import lombok.RequiredArgsConstructor;

/**
 * Consumes the patient lifecycle stream. <b>All patient events share ONE topic</b>
 * ({@code patient-events}) keyed by {@code patientId}, so a patient's events are strictly ordered —
 * register is always processed before delete. (Kafka orders only within a topic-partition, so
 * splitting event types across topics would let a delete overtake its register — verified.) Spring
 * routes each record to the {@link KafkaHandler} whose parameter type matches the Avro record.
 *
 * <p>Both handlers are <b>idempotent</b>: opening a duplicate account and deactivating an
 * already-deactivated (or absent) one are treated as success, never rethrown — so a redelivery is
 * not dead-lettered. Any other failure propagates to the container error handler → retried, then
 * dead-lettered to {@code patient-events.DLT}.
 */
@Component
@RequiredArgsConstructor
@KafkaListener(topics = "patient-events", groupId = "billing-service")
public class PatientEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(PatientEventsConsumer.class);

    private final BillingAccountService accountService;
    private final BillingEventsPublisher billingEventsPublisher;

    @KafkaHandler
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

    @KafkaHandler
    public void onPatientDeleted(PatientDeleted event) {
        UUID patientId = UUID.fromString(event.getPatientId());
        try {
            BillingAccountResponseDTO account = accountService.deactivateForPatient(patientId);
            log.info("Deactivated billing account for deleted patient {} → {} (event {})",
                    patientId, account.status(), event.getEventId());
        } catch (BillingAccountNotFoundException e) {
            log.info("No billing account for deleted patient {} — nothing to deactivate (event {})",
                    patientId, event.getEventId());
        } catch (AccountHasBalanceException e) {
            // Compensation: a patient with a funded account can't be deleted. Ask patient-service to
            // restore the patient (the balance must be settled first).
            billingEventsPublisher.publishDeletionRejected(patientId, "billing account has a non-zero balance");
            log.info("Rejected deletion of patient {} — funded account; published compensation (event {})",
                    patientId, event.getEventId());
        }
    }

    /** An event type we don't handle yet — log and skip rather than fail the partition. */
    @KafkaHandler(isDefault = true)
    public void onUnknown(Object event) {
        log.warn("Ignoring unknown event type on patient-events: {}",
                event == null ? "null" : event.getClass().getName());
    }
}
