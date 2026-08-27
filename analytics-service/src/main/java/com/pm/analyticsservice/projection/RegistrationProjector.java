package com.pm.analyticsservice.projection;

import com.pm.analyticsservice.model.DailyRegistrations;
import com.pm.analyticsservice.model.ProcessedEvent;
import com.pm.analyticsservice.repository.DailyRegistrationsRepository;
import com.pm.analyticsservice.repository.ProcessedEventRepository;
import com.pm.events.PatientRegistered;
import java.time.LocalDate;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Folds a {@link PatientRegistered} event into the {@code daily_registrations} read model.
 *
 * <p><b>Idempotent by construction.</b> Delivery is at-least-once, and {@code count = count + 1} is
 * not naturally idempotent, so the whole step runs in one transaction: check the idempotency ledger
 * ({@link ProcessedEventRepository}) for this {@code eventId}; if present, this is a redelivery —
 * do nothing; otherwise increment the day's bucket and record the {@code eventId}. Both writes
 * commit together, so a crash between them can't leave the count bumped without the guard (or vice
 * versa). The {@code processed_events} primary key is the hard backstop against a double-apply.
 *
 * <p><b>Bucketing:</b> the day is derived from {@code occurredAt} in <b>UTC</b> — a deterministic,
 * single-timezone rollup. (A real reporting system would bucket by the business's local zone; that's
 * a config choice, called out here rather than left implicit.)
 */
@Service
@RequiredArgsConstructor
public class RegistrationProjector {

    private static final Logger log = LoggerFactory.getLogger(RegistrationProjector.class);

    private final DailyRegistrationsRepository dailyRegistrations;
    private final ProcessedEventRepository processedEvents;

    @Transactional
    public void apply(PatientRegistered event) {
        if (processedEvents.existsById(event.getEventId())) {
            log.debug("Registration event {} already applied — skipping (redelivery)", event.getEventId());
            return;
        }

        LocalDate day = event.getOccurredAt().atZone(ZoneOffset.UTC).toLocalDate();
        DailyRegistrations bucket = dailyRegistrations.findById(day)
                .orElseGet(() -> DailyRegistrations.startOn(day));
        bucket.recordOne();
        dailyRegistrations.save(bucket);

        processedEvents.save(ProcessedEvent.of(event.getEventId()));
        log.debug("Projected registration {} into {}", event.getEventId(), day);
    }
}
