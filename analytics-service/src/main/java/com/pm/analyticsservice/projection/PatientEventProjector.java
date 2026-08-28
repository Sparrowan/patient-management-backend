package com.pm.analyticsservice.projection;

import com.pm.analyticsservice.model.ActivePatient;
import com.pm.analyticsservice.model.DailyRegistrations;
import com.pm.analyticsservice.model.ProcessedEvent;
import com.pm.analyticsservice.repository.ActivePatientRepository;
import com.pm.analyticsservice.repository.DailyRegistrationsRepository;
import com.pm.analyticsservice.repository.ProcessedEventRepository;
import com.pm.events.PatientDeleted;
import com.pm.events.PatientRegistered;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Folds patient lifecycle events into the read models. One projector owns <em>all</em> the models an
 * event affects, applied in a single transaction — so "this event has been processed" means every
 * derived table moved together. (Two separate projectors sharing a per-{@code eventId} ledger would
 * be wrong: the first to run would mark the event processed and the second would skip it.)
 *
 * <p><b>Two idempotency strategies, by the model's nature:</b>
 * <ul>
 *   <li>{@code daily_registrations} is a <b>counter</b> — {@code count + 1} is not idempotent, so a
 *       registration is guarded by the {@code processed_events} ledger (skip if the eventId is
 *       already recorded), keeping an at-least-once redelivery from double-counting.</li>
 *   <li>{@code active_patients} is a <b>set</b> — add/remove by id is convergent, so it needs no
 *       ledger: a deletion just removes the id (a no-op if already gone), naturally idempotent.</li>
 * </ul>
 * Bucketing for the daily model is by {@code occurredAt} in <b>UTC</b> (a deterministic rollup).
 */
@Service
@RequiredArgsConstructor
public class PatientEventProjector {

    private static final Logger log = LoggerFactory.getLogger(PatientEventProjector.class);
    private static final String PROJECTED_METRIC = "analytics.events.projected";

    private final DailyRegistrationsRepository dailyRegistrations;
    private final ActivePatientRepository activePatients;
    private final ProcessedEventRepository processedEvents;
    private final MeterRegistry meterRegistry;

    @Transactional
    public void onPatientRegistered(PatientRegistered event) {
        if (processedEvents.existsById(event.getEventId())) {
            log.debug("Registration {} already applied — skipping (redelivery)", event.getEventId());
            projected("registered", "skipped");
            return;
        }

        LocalDate day = event.getOccurredAt().atZone(ZoneOffset.UTC).toLocalDate();
        DailyRegistrations bucket = dailyRegistrations.findById(day)
                .orElseGet(() -> DailyRegistrations.startOn(day));
        bucket.recordOne();
        dailyRegistrations.save(bucket);

        activePatients.save(ActivePatient.of(event.getPatientId())); // add to the active set

        processedEvents.save(ProcessedEvent.of(event.getEventId()));
        log.debug("Projected registration {} into {}", event.getEventId(), day);
        projected("registered", "applied");
    }

    @Transactional
    public void onPatientDeleted(PatientDeleted event) {
        // Convergent set removal — idempotent on its own, so no ledger guard: removing an id that is
        // already gone is a no-op. (Ordering holds: patient-events is keyed by patientId, so a
        // patient's registration always precedes its deletion within the partition.)
        activePatients.removeById(event.getPatientId());
        log.debug("Projected deletion {} — removed patient {} from active set",
                event.getEventId(), event.getPatientId());
        projected("deleted", "applied");
    }

    /** Counts projected events by type (registered/deleted) and outcome (applied/skipped). */
    private void projected(String type, String outcome) {
        meterRegistry.counter(PROJECTED_METRIC, "type", type, "outcome", outcome).increment();
    }
}
