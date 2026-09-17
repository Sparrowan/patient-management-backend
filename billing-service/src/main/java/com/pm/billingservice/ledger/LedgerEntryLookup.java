package com.pm.billingservice.ledger;

import com.pm.billingservice.model.LedgerRecord;
import com.pm.billingservice.repository.ArchivedLedgerEntryRepository;
import com.pm.billingservice.repository.LedgerEntryRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads a money movement across both halves of the ledger — hot {@code ledger_entries} and cold
 * {@code ledger_entries_archive}.
 *
 * <p><b>Why this class has to exist.</b> A single-table ledger got its idempotency guarantee from
 * one {@code UNIQUE (idempotency_key)} constraint: even if application code forgot to check, the
 * database refused the second insert. Splitting the table splits that constraint — each half
 * enforces uniqueness only <em>within itself</em>, so an insert into the hot table will happily
 * succeed against a key that already exists in the archive. Part of the guarantee therefore moves
 * out of the database and into this code, which is a real cost of archival and worth naming rather
 * than discovering.
 */
@Component
@RequiredArgsConstructor
public class LedgerEntryLookup {

    private final LedgerEntryRepository ledgerRepository;
    private final ArchivedLedgerEntryRepository archiveRepository;

    /**
     * Finds the movement recorded under this idempotency key, in either half.
     *
     * <p><b>The order of the two reads is the correctness argument, not a style choice.</b> Rows
     * move in exactly one direction — hot to archive — and atomically, so at any instant a key is
     * in exactly one of the two tables, never both and never neither. Reading hot first is
     * therefore safe under a fresh snapshot per statement (which is what {@code READ_COMMITTED}
     * gives the money paths): a miss on hot means the row either never existed, or was already
     * moved and its archive insert is committed — so the second read finds it.
     *
     * <p>Reversing the order opens a genuine double-apply window: read archive (miss, not moved
     * yet), the archiver commits the move, read hot (miss, just deleted) — the caller concludes
     * "new request" and applies the money twice.
     *
     * <p>In practice this second read almost never fires: clients retry within minutes, and the
     * archive cutoff is months. That is by design — <b>the cutoff must stay far larger than the
     * longest retry window</b>, so the archive lookup is a correctness backstop rather than a load
     * path.
     */
    public Optional<LedgerRecord> findByIdempotencyKey(String idempotencyKey) {
        Optional<LedgerRecord> hot =
                ledgerRepository.findByIdempotencyKey(idempotencyKey).map(LedgerRecord.class::cast);
        return hot.isPresent()
                ? hot
                : archiveRepository.findByIdempotencyKey(idempotencyKey).map(LedgerRecord.class::cast);
    }
}
