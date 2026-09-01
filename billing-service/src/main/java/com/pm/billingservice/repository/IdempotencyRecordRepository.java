package com.pm.billingservice.repository;

import com.pm.billingservice.model.IdempotencyRecord;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Store for {@link IdempotencyRecord}s. Lookups are always by the natural key {@code (userSub, idKey)}
 * — the same pair the DB unique constraint enforces — so a replay finds the winning claim.
 */
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, java.util.UUID> {

    Optional<IdempotencyRecord> findByUserSubAndIdKey(String userSub, String idKey);

    /** Bulk-remove expired records — called by the retention sweep (see the scheduled cleanup). */
    @Modifying
    @Query("delete from IdempotencyRecord r where r.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
