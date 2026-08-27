package com.pm.billingservice.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pm.billingservice.model.Payout;

/** Data access for {@link Payout}. The unique {@code idempotencyKey} backs replay detection. */
public interface PayoutRepository extends JpaRepository<Payout, UUID> {

    Optional<Payout> findByIdempotencyKey(String idempotencyKey);

    /**
     * The saga worker's claim: PENDING payouts due for a settlement attempt ({@code next_attempt_at}
     * reached), oldest first, capped. Native {@code FOR UPDATE SKIP LOCKED} so workers across replicas
     * each grab a <b>disjoint</b> batch — a second worker skips the rows the first has locked rather
     * than blocking or double-settling. The lock is held until the worker's transaction commits.
     * Row-precise locking depends on {@code idx_payouts_due (status, next_attempt_at)}; without it the
     * DB filesorts and over-locks the batch. (Same pattern as the patient-service outbox relay.)
     */
    @Query(value = "SELECT * FROM payouts WHERE status = 'PENDING' AND next_attempt_at <= :now "
            + "ORDER BY next_attempt_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<Payout> claimDuePending(@Param("now") Instant now, @Param("limit") int limit);
}
