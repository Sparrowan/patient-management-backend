package com.pm.billingservice.ledger;

import com.pm.billingservice.repository.LedgerEntryRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves one batch of aged ledger entries from the hot table to the archive, atomically.
 *
 * <p>Its own bean, rather than a method on {@link LedgerArchiveWorker}, because a self-invoked
 * {@code @Transactional} method does not go through the Spring proxy and would silently run with no
 * transaction at all — which here would mean a copy that commits and a delete that does not, i.e.
 * the same entry in both tables, breaking the invariant every ledger read depends on.
 */
@Component
@RequiredArgsConstructor
public class LedgerBatchArchiver {

    private final LedgerEntryRepository ledgerRepository;

    /**
     * Copies then deletes the oldest entries older than {@code cutoff}, up to {@code limit}.
     *
     * <p>One transaction, so a row is never in both tables and never in neither — the guarantee
     * {@link LedgerEntryLookup}'s hot-then-archive read order is built on. A crash mid-move rolls
     * back and the next sweep simply retries.
     *
     * @return how many entries moved
     */
    @Transactional
    public int moveBatch(Instant cutoff, int limit) {
        int copied = ledgerRepository.copyOldestToArchiveBefore(cutoff, limit);
        if (copied == 0) {
            return 0;
        }
        int deleted = ledgerRepository.deleteOldestBefore(cutoff, limit);
        if (deleted != copied) {
            // Belt and braces: the two statements share a WHERE/ORDER BY/LIMIT and the ledger is
            // append-only, so this cannot differ. If it ever does, the rollback is the only safe
            // outcome — committing would leave rows duplicated across the seam.
            throw new IllegalStateException(
                    "Ledger archive batch mismatch: copied %d but deleted %d".formatted(copied, deleted));
        }
        return copied;
    }
}
