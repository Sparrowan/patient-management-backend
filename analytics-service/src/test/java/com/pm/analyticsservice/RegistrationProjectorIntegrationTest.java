package com.pm.analyticsservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.pm.analyticsservice.projection.RegistrationProjector;
import com.pm.analyticsservice.repository.DailyRegistrationsRepository;
import com.pm.analyticsservice.repository.ProcessedEventRepository;
import com.pm.events.PatientRegistered;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The core of the read side: projecting events into the aggregate, and doing so idempotently so an
 * at-least-once redelivery can't double-count.
 */
@DisplayName("RegistrationProjector (integration)")
class RegistrationProjectorIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RegistrationProjector projector;
    @Autowired
    private DailyRegistrationsRepository dailyRegistrations;
    @Autowired
    private ProcessedEventRepository processedEvents;

    @BeforeEach
    void reset() {
        dailyRegistrations.deleteAll();
        processedEvents.deleteAll();
    }

    private PatientRegistered event(String eventId, Instant occurredAt) {
        return PatientRegistered.newBuilder()
                .setEventId(eventId)
                .setPatientId(UUID.randomUUID().toString())
                .setCurrency("USD")
                .setOccurredAt(occurredAt)
                .setActor("system")
                .build();
    }

    @Test
    @DisplayName("counts distinct events into the day bucket and ignores a redelivered event")
    void projectsAndDeduplicates() {
        Instant occurredAt = Instant.parse("2026-08-27T10:15:30Z");
        LocalDate day = occurredAt.atZone(ZoneOffset.UTC).toLocalDate();

        String firstEventId = UUID.randomUUID().toString();
        projector.apply(event(firstEventId, occurredAt));
        projector.apply(event(firstEventId, occurredAt));                 // redelivery — must not count
        projector.apply(event(UUID.randomUUID().toString(), occurredAt)); // a distinct event, same day

        assertThat(dailyRegistrations.findById(day).orElseThrow().getRegistrations()).isEqualTo(2);
    }
}
