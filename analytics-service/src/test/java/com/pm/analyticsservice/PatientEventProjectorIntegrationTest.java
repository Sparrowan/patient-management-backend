package com.pm.analyticsservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.pm.analyticsservice.projection.PatientEventProjector;
import com.pm.analyticsservice.repository.ActivePatientRepository;
import com.pm.analyticsservice.repository.DailyRegistrationsRepository;
import com.pm.analyticsservice.repository.ProcessedEventRepository;
import com.pm.events.PatientDeleted;
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
 * The projection core, folding both event types across both read models — and the two idempotency
 * strategies: the counter ({@code daily_registrations}) guarded by the ledger, and the convergent set
 * ({@code active_patients}) that is idempotent on its own.
 */
@DisplayName("PatientEventProjector (integration)")
class PatientEventProjectorIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PatientEventProjector projector;
    @Autowired
    private DailyRegistrationsRepository dailyRegistrations;
    @Autowired
    private ActivePatientRepository activePatients;
    @Autowired
    private ProcessedEventRepository processedEvents;

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-27T10:15:30Z");
    private static final LocalDate DAY = OCCURRED_AT.atZone(ZoneOffset.UTC).toLocalDate();

    @BeforeEach
    void reset() {
        dailyRegistrations.deleteAll();
        activePatients.deleteAll();
        processedEvents.deleteAll();
    }

    private PatientRegistered registered(String eventId, String patientId) {
        return PatientRegistered.newBuilder()
                .setEventId(eventId).setPatientId(patientId)
                .setCurrency("USD").setOccurredAt(OCCURRED_AT).setActor("system")
                .build();
    }

    private PatientDeleted deleted(String eventId, String patientId) {
        return PatientDeleted.newBuilder()
                .setEventId(eventId).setPatientId(patientId).setOccurredAt(OCCURRED_AT)
                .build();
    }

    @Test
    @DisplayName("a registration bumps the daily counter and adds to the active set; redelivery is a no-op")
    void registrationIsIdempotent() {
        String eventId = UUID.randomUUID().toString();
        String patientId = UUID.randomUUID().toString();

        projector.onPatientRegistered(registered(eventId, patientId));
        projector.onPatientRegistered(registered(eventId, patientId)); // redelivery

        assertThat(dailyRegistrations.findById(DAY).orElseThrow().getRegistrations()).isEqualTo(1);
        assertThat(activePatients.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a deletion removes the patient from the active set (counter untouched); redelivery is a no-op")
    void deletionRemovesFromActiveSet() {
        String patientId = UUID.randomUUID().toString();
        projector.onPatientRegistered(registered(UUID.randomUUID().toString(), patientId));

        projector.onPatientDeleted(deleted(UUID.randomUUID().toString(), patientId));
        projector.onPatientDeleted(deleted(UUID.randomUUID().toString(), patientId)); // already gone — no throw

        assertThat(activePatients.count()).isZero();
        // the historical registration count is a record of the past — a deletion does not rewrite it
        assertThat(dailyRegistrations.findById(DAY).orElseThrow().getRegistrations()).isEqualTo(1);
    }

    @Test
    @DisplayName("active count is registrations minus deletions")
    void activeCountReflectsNetMembership() {
        String p1 = UUID.randomUUID().toString();
        String p2 = UUID.randomUUID().toString();
        projector.onPatientRegistered(registered(UUID.randomUUID().toString(), p1));
        projector.onPatientRegistered(registered(UUID.randomUUID().toString(), p2));
        projector.onPatientDeleted(deleted(UUID.randomUUID().toString(), p1));

        assertThat(activePatients.count()).isEqualTo(1); // only p2 remains
    }
}
