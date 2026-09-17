package com.pm.patientservice.outbox;

import com.pm.patientservice.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Prunes the transactional outbox. Every event ever published is still sitting in
 * {@code outbox_events}; without retention the table grows forever, which slowly taxes the relay's
 * index scans, the backups, and the disk — the classic "append-only table nobody ever cleans up".
 *
 * <p><b>What is safe to delete:</b> only rows that were actually published, and only once they're
 * older than {@link #RETENTION}. An unpublished row is pending work — deleting it would silently drop
 * an event. The window exists so the rows stay available for a while for debugging and incident
 * forensics ("was this event ever emitted?"); it is not an audit log, the Kafka topic is.
 *
 * <p><b>Why batches, not one DELETE:</b> a single unbounded delete holds locks for its whole duration,
 * balloons the undo log, and produces one huge binlog transaction that the replica must replay before
 * anything else — a lag spike. Small batches keep locks short and replication smooth, and
 * {@link #MAX_BATCHES_PER_RUN} bounds how much work one tick can do so a large backlog is drained over
 * several ticks instead of one long stall.
 *
 * <p><b>Multi-instance safe by nature:</b> no {@code SKIP LOCKED} claim is needed (unlike the relay).
 * Deleting already-terminal rows is idempotent — if two replicas overlap, the second simply deletes
 * fewer rows. Split from {@link OutboxRetentionScheduler} so the logic is testable without the
 * scheduler firing underneath it.
 */
@Component
@RequiredArgsConstructor
public class OutboxRetentionWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxRetentionWorker.class);

    /** How long a published row is kept before it's pruned. */
    static final Duration RETENTION = Duration.ofDays(7);
    /** Rows per delete statement — small enough to keep locks and binlog events short. */
    static final int BATCH_SIZE = 500;
    /** Cap on batches per run, so one tick can't turn into a long-running delete. */
    static final int MAX_BATCHES_PER_RUN = 20;

    private final OutboxEventRepository outboxRepository;
    private final MeterRegistry meterRegistry;

    /** Deletes expired published rows in bounded batches; returns how many were removed. */
    public int purgeExpired() {
        Instant cutoff = Instant.now().minus(RETENTION);
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int deleted = outboxRepository.deletePublishedBefore(cutoff, BATCH_SIZE);
            total += deleted;
            if (deleted < BATCH_SIZE) {
                break; // drained — nothing older than the cutoff left
            }
        }
        if (total > 0) {
            meterRegistry.counter("patient.outbox.retention.deleted").increment(total);
            log.info("Outbox retention: pruned {} published event(s) older than {}", total, cutoff);
        }
        return total;
    }
}
