package com.pm.analyticsservice.service;

import com.pm.analyticsservice.messaging.PatientEventsConsumer;
import com.pm.analyticsservice.repository.ActivePatientRepository;
import com.pm.analyticsservice.repository.DailyRegistrationsRepository;
import com.pm.analyticsservice.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rebuilds every read model from scratch by replaying the event log — the CQRS payoff: because the
 * read models are <em>derived</em> and the Kafka topic is the source of truth, they're disposable.
 *
 * <p>Two steps: (1) truncate the read models <b>and the idempotency ledger</b> (the ledger must go
 * too, or replay would treat every event as already-applied and skip it); (2) seek the consumer group
 * back to the start of the topic, so the whole history is re-delivered and re-projected. Replay runs
 * on the consumer thread afterward, so the read models are <b>eventually consistent</b> while it
 * proceeds — callers get 202, not a finished result.
 */
@Service
@RequiredArgsConstructor
public class ProjectionRebuildService {

    private static final Logger log = LoggerFactory.getLogger(ProjectionRebuildService.class);

    private final DailyRegistrationsRepository dailyRegistrations;
    private final ActivePatientRepository activePatients;
    private final ProcessedEventRepository processedEvents;
    private final PatientEventsConsumer consumer;

    public void rebuild() {
        clearReadModels();
        consumer.seekToBeginning();
        log.info("Read models + idempotency ledger cleared; consumer reset to the beginning — replay in progress");
    }

    @Transactional
    public void clearReadModels() {
        dailyRegistrations.deleteAllInBatch();
        activePatients.deleteAllInBatch();
        processedEvents.deleteAllInBatch();
    }
}
