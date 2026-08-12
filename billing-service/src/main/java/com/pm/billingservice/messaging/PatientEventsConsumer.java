package com.pm.billingservice.messaging;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.pm.billingservice.dto.OpenAccountRequestDTO;
import com.pm.billingservice.exception.AccountAlreadyExistsException;
import com.pm.billingservice.service.BillingAccountService;
import com.pm.events.PatientDeleted;
import com.pm.events.PatientRegistered;

import lombok.RequiredArgsConstructor;

/**
 * Consumes the patient lifecycle stream ({@code patient-events}). Spring routes each record to the
 * {@link KafkaHandler} whose parameter type matches the Avro record.
 *
 * <p><b>Registration is async</b>: {@code PatientRegistered} opens the account here, idempotently
 * (a duplicate is treated as success, never dead-lettered). <b>Deletion is synchronous</b>: it is
 * handled by the gRPC veto ({@code CloseAccountForPatient}), which closes the account before the
 * patient is deleted — so {@code PatientDeleted} is <b>fan-out only</b> here (billing takes no
 * action; the handler exists just so the shared topic doesn't route it to the default handler).
 */
@Component
@RequiredArgsConstructor
@KafkaListener(topics = "patient-events", groupId = "billing-service")
public class PatientEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(PatientEventsConsumer.class);

    private final BillingAccountService accountService;

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
        // Fan-out only — the account was already closed synchronously via the gRPC veto.
        log.debug("PatientDeleted for {} — no billing action (closed synchronously)", event.getPatientId());
    }

    /** An event type we don't handle yet — log and skip rather than fail the partition. */
    @KafkaHandler(isDefault = true)
    public void onUnknown(Object event) {
        log.warn("Ignoring unknown event type on patient-events: {}",
                event == null ? "null" : event.getClass().getName());
    }
}
