package com.pm.billingservice.ledger;

import com.pm.billingservice.exception.UnsupportedLedgerSortException;
import com.pm.billingservice.model.LedgerRecord;
import com.pm.billingservice.pagination.Cursor;
import com.pm.billingservice.repository.ArchivedLedgerEntryRepository;
import com.pm.billingservice.repository.LedgerEntryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Reads an account's money-movement history as one list, spanning the hot {@code ledger_entries}
 * table and the cold {@code ledger_entries_archive}.
 *
 * <p><b>Why this is concatenation and not a merge sort.</b> The archiver moves rows strictly
 * oldest-first, so the archive always holds a <em>prefix</em> of the account's time-ordered history
 * and the hot table holds the suffix — the two tables are time-disjoint. Read newest-first, that
 * means every hot row precedes every archived row, so the halves can simply be concatenated. This
 * is a contract the archiver must keep: if it ever moved rows out of time order, every read here
 * would silently interleave wrongly.
 */
@Component
@RequiredArgsConstructor
public class LedgerHistoryReader {

    private final LedgerEntryRepository ledgerRepository;
    private final ArchivedLedgerEntryRepository archiveRepository;

    /**
     * One page of history, newest first.
     *
     * <p><b>Must run inside a transaction</b> (the caller's {@code @Transactional(readOnly = true)}).
     * The count and the two slices are separate statements; without a single consistent snapshot, an
     * archiver commit landing between them would shift the seam underneath the reader and duplicate
     * or drop a row at the boundary.
     */
    public Page<LedgerRecord> read(UUID accountId, Pageable pageable) {
        requireTimeOrdering(pageable.getSort());

        long hotCount = ledgerRepository.countByAccountId(accountId);
        long total = hotCount + archiveRepository.countByAccountId(accountId);

        int size = pageable.getPageSize();
        long offset = pageable.getOffset();
        List<LedgerRecord> content = new ArrayList<>(size);

        // The page begins in the hot half (possibly running off its end).
        if (offset < hotCount) {
            content.addAll(ledgerRepository.findSlice(accountId, size, offset));
        }
        // Whatever the hot half could not supply continues from the head of the archive — or, for a
        // page that starts past the seam entirely, from the corresponding offset inside it.
        int remaining = size - content.size();
        if (remaining > 0) {
            content.addAll(archiveRepository.findSlice(accountId, remaining, Math.max(0, offset - hotCount)));
        }

        return new PageImpl<>(content, pageable, total);
    }

    /**
     * One keyset page, newest first, spanning both halves.
     *
     * <p>Simpler than the offset read above, and that is the point: no counts, no seam arithmetic.
     * <b>The same cursor is valid against both tables unchanged</b>, because every archived row is
     * older than every hot row — so "everything older than this position" in the archive is always
     * the correct continuation, whether the cursor currently sits in the hot half or has already
     * crossed into the cold one. Once it has crossed, the hot query returns nothing on its own (all
     * hot rows are <em>newer</em> than the cursor, so the predicate excludes them), and the read
     * falls through to the archive with no special case to write.
     *
     * @param position where to resume, or {@code null} for the first page
     * @param fetchSize rows to fetch, normally {@code limit + 1} so the caller can detect a further
     *     page without a COUNT
     */
    public List<LedgerRecord> readKeyset(UUID accountId, Cursor position, int fetchSize) {
        List<LedgerRecord> rows = new ArrayList<>(fetchSize);
        Limit fetch = Limit.of(fetchSize);

        rows.addAll(position == null
                ? ledgerRepository.findFirstPage(accountId, fetch)
                : ledgerRepository.findPageAfter(accountId, position.createdAt(), position.id(), fetch));

        int remaining = fetchSize - rows.size();
        if (remaining > 0) {
            Limit topUp = Limit.of(remaining);
            rows.addAll(position == null
                    ? archiveRepository.findFirstPage(accountId, topUp)
                    : archiveRepository.findPageAfter(
                            accountId, position.createdAt(), position.id(), topUp));
        }
        return rows;
    }

    /**
     * Ledger history is ordered by time, and only by time.
     *
     * <p>Splitting a table across a time boundary buys cheap reads at one price: the ordering that
     * makes the split invisible is the one the split is keyed on. Sorting by, say, {@code amount}
     * across both halves would mean reading both in full and merging in memory — exactly the
     * unbounded work archival exists to avoid. So the endpoint narrows to time order and says so,
     * rather than silently returning a page sorted only within whichever half it happened to read.
     */
    private void requireTimeOrdering(Sort sort) {
        boolean timeOrdered = sort.isUnsorted()
                || sort.stream().allMatch(order -> "createdAt".equals(order.getProperty()));
        if (!timeOrdered) {
            throw new UnsupportedLedgerSortException(
                    sort.stream().map(Sort.Order::getProperty).toList());
        }
    }
}
