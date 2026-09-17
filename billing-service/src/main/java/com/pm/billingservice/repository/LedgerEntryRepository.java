package com.pm.billingservice.repository;

import com.pm.billingservice.model.LedgerEntry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Append-only data access for {@link LedgerEntry} — the hot half of the ledger. */
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    /**
     * Idempotency lookup against the hot half only. Callers on a money path must go through
     * {@code LedgerEntryLookup} instead, which also reads the archive.
     */
    Optional<LedgerEntry> findByIdempotencyKey(String idempotencyKey);

    /** Offset pagination — kept for admin/random-access use (page numbers, totals). */
    Page<LedgerEntry> findByAccountId(UUID accountId, Pageable pageable);

    /**
     * Keyset first page: newest entries for an account. Backed by
     * {@code idx_ledger_account_created_id}. Fetch {@code limit + 1} to detect a further page.
     */
    @Query("""
            select l from LedgerEntry l
            where l.accountId = :accountId
            order by l.createdAt desc, l.id desc
            """)
    List<LedgerEntry> findFirstPage(@Param("accountId") UUID accountId, Limit limit);

    /**
     * Keyset next page: entries strictly older than the cursor position {@code (ts, id)}. The tuple
     * comparison is expanded (JPQL has no row-value syntax) but semantically
     * {@code (created_at, id) < (:ts, :id)} under the {@code created_at DESC, id DESC} order.
     */
    @Query("""
            select l from LedgerEntry l
            where l.accountId = :accountId
              and (l.createdAt < :ts or (l.createdAt = :ts and l.id < :id))
            order by l.createdAt desc, l.id desc
            """)
    List<LedgerEntry> findPageAfter(
            @Param("accountId") UUID accountId,
            @Param("ts") Instant ts,
            @Param("id") UUID id,
            Limit limit);

    long countByAccountId(UUID accountId);

    /**
     * An arbitrary offset/limit slice in canonical history order.
     *
     * <p>Spring Data's {@code Pageable} can only express offsets that are a whole multiple of the
     * page size, which is not enough here: a page that straddles the hot/archive seam has to read
     * its tail from one table and its head from the other at offsets neither table chose. Hence the
     * explicit {@code LIMIT}/{@code OFFSET}.
     */
    @Query(value = "SELECT * FROM ledger_entries WHERE account_id = :accountId "
            + "ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<LedgerEntry> findSlice(
            @Param("accountId") UUID accountId, @Param("limit") int limit, @Param("offset") long offset);

    /**
     * When the oldest money movement still attached to an unsettled payout happened, or {@code null}
     * if there is none. Archiving must not pass this point — see {@code LedgerArchiveWorker}.
     */
    @Query(value = "SELECT MIN(le.created_at) FROM ledger_entries le "
            + "JOIN payouts p ON p.id = le.payout_id "
            + "WHERE p.status IN ('PENDING', 'FAILED')", nativeQuery = true)
    Instant findOldestUnsettledMovementTime();

    /**
     * Copies the oldest batch of entries older than {@code cutoff} into the archive.
     *
     * <p>Pairs with {@link #deleteOldestBefore} on the identical {@code WHERE}/{@code ORDER BY}/
     * {@code LIMIT}, so the two statements act on exactly the same rows. That is only sound because
     * the ledger is append-only: nothing updates a row, nothing else deletes one, and any insert
     * arriving mid-transaction carries {@code created_at = now}, far beyond the cutoff. On a mutable
     * table this pairing would be a race.
     */
    @Modifying
    @Query(value = "INSERT INTO ledger_entries_archive "
            + "(id, account_id, type, amount, balance_after, idempotency_key, description, "
            + "transfer_id, payout_id, created_at, updated_at, created_by, updated_by, version, archived_at) "
            + "SELECT id, account_id, type, amount, balance_after, idempotency_key, description, "
            + "transfer_id, payout_id, created_at, updated_at, created_by, updated_by, version, "
            + "CURRENT_TIMESTAMP(6) "
            + "FROM ledger_entries WHERE created_at < :cutoff "
            + "ORDER BY created_at ASC, id ASC LIMIT :limit", nativeQuery = true)
    int copyOldestToArchiveBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /** The second half of the move. Must run in the same transaction as the copy. */
    @Modifying
    @Query(value = "DELETE FROM ledger_entries WHERE created_at < :cutoff "
            + "ORDER BY created_at ASC, id ASC LIMIT :limit", nativeQuery = true)
    int deleteOldestBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
