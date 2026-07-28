package com.pm.billingservice.repository;

import com.pm.billingservice.model.LedgerEntry;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only data access for {@link LedgerEntry}. */
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    /** Idempotency lookup: a matching key means the movement was already applied. */
    Optional<LedgerEntry> findByIdempotencyKey(String idempotencyKey);

    Page<LedgerEntry> findByAccountId(UUID accountId, Pageable pageable);
}
