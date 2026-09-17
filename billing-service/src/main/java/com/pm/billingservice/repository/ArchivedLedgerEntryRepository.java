package com.pm.billingservice.repository;

import com.pm.billingservice.model.ArchivedLedgerEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Reads of the cold ledger.
 *
 * <p>Extends the bare {@link Repository} marker rather than {@code JpaRepository} on purpose: the
 * archive is written only by the archiver's {@code INSERT ... SELECT}, so inheriting
 * {@code save}/{@code delete} would hand every caller a write path that must not exist. The
 * interface exposes exactly the two reads the archive has to answer.
 */
public interface ArchivedLedgerEntryRepository extends Repository<ArchivedLedgerEntry, UUID> {

    /**
     * Idempotency lookup against the cold half. A key that has aged out of {@code ledger_entries}
     * is still a key that was used — missing this is a double-apply.
     */
    Optional<ArchivedLedgerEntry> findByIdempotencyKey(String idempotencyKey);

    Page<ArchivedLedgerEntry> findByAccountId(UUID accountId, Pageable pageable);

    long countByAccountId(UUID accountId);

    /** The cold-half counterpart of {@code LedgerEntryRepository.findSlice}, same ordering. */
    @Query(value = "SELECT * FROM ledger_entries_archive WHERE account_id = :accountId "
            + "ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<ArchivedLedgerEntry> findSlice(
            @Param("accountId") UUID accountId, @Param("limit") int limit, @Param("offset") long offset);
}
