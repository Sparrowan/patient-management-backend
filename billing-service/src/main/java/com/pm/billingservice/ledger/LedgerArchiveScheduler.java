package com.pm.billingservice.ledger;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires {@link LedgerArchiveWorker} on a timer.
 *
 * <p>Split from the worker so tests can drive the archiving logic directly without a scheduled tick
 * racing their fixtures — the same split as the payout saga worker and the outbox relay.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ledger.archive", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LedgerArchiveScheduler {

    private final LedgerArchiveWorker worker;

    /** Daily by default: the hot window is months wide, so there is nothing to gain from sweeping often. */
    @Scheduled(fixedDelayString = "${ledger.archive.sweep-interval-ms:86400000}")
    public void sweep() {
        worker.archiveExpired();
    }
}
