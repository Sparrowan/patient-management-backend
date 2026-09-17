package com.pm.billingservice.ledger;

import com.pm.billingservice.repository.LedgerEntryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Moves aged entries out of the hot ledger and into {@code ledger_entries_archive}.
 *
 * <p><b>This is a tuning knob, not a correctness knob.</b> A cutoff set too short or too long makes
 * reads slower or the hot table bigger; nothing breaks and no money moves. That is deliberate — it
 * means the window can be changed in configuration without a migration or a backfill.
 *
 * <p><b>Why no {@code SKIP LOCKED} here</b>, unlike the outbox relay and the payout saga worker. Those
 * want N instances working on <em>disjoint</em> batches. This one must not: the archive has to stay a
 * strict time-ordered <em>prefix</em> of each account's history, because
 * {@link LedgerHistoryReader} relies on the two tables being time-disjoint to concatenate rather
 * than merge them. Batch selection is deterministic (oldest first), so two instances racing pick the
 * identical batch, the primary key arbitrates, and the loser rolls back <em>wholly</em> — no holes.
 * Disjoint batches would risk one committing and one failing, leaving a gap mid-history.
 */
@Component
@RequiredArgsConstructor
public class LedgerArchiveWorker {

    private static final Logger log = LoggerFactory.getLogger(LedgerArchiveWorker.class);

    /** Kept small so each move is a short transaction — a huge one spikes replica lag via the binlog. */
    static final int BATCH_SIZE = 500;

    /** Bounds a single sweep so a large backlog drains over several runs instead of one long one. */
    static final int MAX_BATCHES_PER_RUN = 20;

    private final LedgerEntryRepository ledgerRepository;
    private final LedgerBatchArchiver batchArchiver;
    private final MeterRegistry meterRegistry;

    /**
     * How long a movement stays hot. Thirteen months by default rather than twelve, so "the same
     * month last year" is still a hot read with a margin.
     *
     * <p>The floor on this value is the longest window in which a client might retry a request: the
     * archive lookup in {@link LedgerEntryLookup} is meant to be a correctness backstop, not a load
     * path. Months against minutes leaves that floor comfortably clear.
     */
    @Value("${ledger.archive.retention-months:13}")
    private int retentionMonths;

    /** @return how many entries were moved */
    public int archiveExpired() {
        Instant cutoff = effectiveCutoff();
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int moved = batchArchiver.moveBatch(cutoff, BATCH_SIZE);
            if (moved == 0) {
                break;
            }
            total += moved;
        }
        if (total > 0) {
            meterRegistry.counter("billing.ledger.archive.moved").increment(total);
            log.info("Archived {} ledger entries older than {}", total, cutoff);
        }
        return total;
    }

    /**
     * The age cutoff, pulled back to just before the oldest movement still attached to an unsettled
     * payout.
     *
     * <p>A {@code PENDING} or parked {@code FAILED} payout may still be compensated — reconciliation
     * can reverse it and write a credit leg, which wants its sibling in the same table. So archiving
     * <b>stops at</b> the oldest such entry rather than skipping past it. Skipping would leave the
     * hot table holding a row older than rows already in the archive, breaking the time-disjointness
     * {@link LedgerHistoryReader} concatenates on. Halting instead is self-healing: the moment the
     * payout resolves, the next sweep moves everything it was holding back.
     *
     * <p>Transfers need no equivalent check while {@code TransferStatus} has a single terminal value;
     * if it ever grows an in-flight state, this is where the same guard belongs.
     */
    private Instant effectiveCutoff() {
        Instant byAge = Instant.now().minus(retentionMonths * 30L, ChronoUnit.DAYS);
        Instant oldestUnsettled = ledgerRepository.findOldestUnsettledMovementTime();
        if (oldestUnsettled != null && oldestUnsettled.isBefore(byAge)) {
            log.debug("Archive cutoff held back to {} by an unsettled payout", oldestUnsettled);
            return oldestUnsettled;
        }
        return byAge;
    }
}
