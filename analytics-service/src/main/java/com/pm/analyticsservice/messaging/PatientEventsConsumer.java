package com.pm.analyticsservice.messaging;

import com.pm.analyticsservice.projection.PatientEventProjector;
import com.pm.events.PatientDeleted;
import com.pm.events.PatientRegistered;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.AbstractConsumerSeekAware;
import org.springframework.stereotype.Component;

/**
 * Consumes the patient lifecycle stream ({@code patient-events}) in analytics-service's own consumer
 * group and drives the read-model projections. Spring routes each record to the {@link KafkaHandler}
 * whose parameter type matches the Avro record.
 *
 * <p>Extends {@link AbstractConsumerSeekAware} so a rebuild can reposition the group to the start of
 * the topic ({@link #seekToBeginning()}) and re-project the entire history — the mechanism behind
 * {@link com.pm.analyticsservice.service.ProjectionRebuildService}.
 */
@Component
@RequiredArgsConstructor
@KafkaListener(topics = "patient-events", groupId = "analytics-service")
public class PatientEventsConsumer extends AbstractConsumerSeekAware {

    private static final Logger log = LoggerFactory.getLogger(PatientEventsConsumer.class);

    private final PatientEventProjector projector;

    @KafkaHandler
    public void onPatientRegistered(PatientRegistered event) {
        projector.onPatientRegistered(event);
    }

    @KafkaHandler
    public void onPatientDeleted(PatientDeleted event) {
        projector.onPatientDeleted(event);
    }

    /** An event type we don't handle — log and skip rather than fail the partition. */
    @KafkaHandler(isDefault = true)
    public void onUnknown(Object event) {
        log.warn("Ignoring unknown event type on patient-events: {}",
                event == null ? "null" : event.getClass().getName());
    }
}
