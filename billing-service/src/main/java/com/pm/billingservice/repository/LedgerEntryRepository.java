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
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Append-only data access for {@link LedgerEntry}. */
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    /** Idempotency lookup: a matching key means the movement was already applied. */
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
}
