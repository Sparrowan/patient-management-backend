package com.pm.analyticsservice.messaging;

import com.pm.analyticsservice.projection.RegistrationProjector;
import com.pm.events.PatientDeleted;
import com.pm.events.PatientRegistered;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the patient lifecycle stream ({@code patient-events}) in analytics-service's own consumer
 * group and drives the read-model projections. Spring routes each record to the {@link KafkaHandler}
 * whose parameter type matches the Avro record; multiple event types share the topic (hence the
 * class-level listener + a handler per type).
 */
@Component
@RequiredArgsConstructor
@KafkaListener(topics = "patient-events", groupId = "analytics-service")
public class PatientEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(PatientEventsConsumer.class);

    private final RegistrationProjector registrationProjector;

    @KafkaHandler
    public void onPatientRegistered(PatientRegistered event) {
        registrationProjector.apply(event);
    }

    @KafkaHandler
    public void onPatientDeleted(PatientDeleted event) {
        // Consumed so the shared topic doesn't route it to the default handler. The active-patients
        // projection that reacts to deletions arrives in a later bit.
        log.debug("PatientDeleted {} — no projection yet", event.getEventId());
    }

    /** An event type we don't handle — log and skip rather than fail the partition. */
    @KafkaHandler(isDefault = true)
    public void onUnknown(Object event) {
        log.warn("Ignoring unknown event type on patient-events: {}",
                event == null ? "null" : event.getClass().getName());
    }
}
