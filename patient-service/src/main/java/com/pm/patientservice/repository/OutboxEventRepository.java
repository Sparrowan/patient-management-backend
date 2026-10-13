package com.pm.patientservice.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.pm.patientservice.model.OutboxEvent;

/**
 * Persistence for the transactional outbox. The relay reads a bounded, oldest-first batch of
 * not-yet-published rows each poll.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims the next batch for publishing by stamping a lease on it, and returns how many rows were
     * won. Pair with {@link #findByClaimToken} to read them back.
     *
     * <p><b>The UPDATE itself is the claim</b> — atomic, so two relays racing cannot both win a row:
     * the second sees {@code claimed_until} already in the future and its predicate no longer
     * matches. This replaces the previous {@code SELECT … FOR UPDATE SKIP LOCKED}, where the held
     * <em>lock</em> was the claim and therefore had to outlive the Kafka send.
     *
     * <p><b>Why that mattered.</b> Holding the claim across the send meant holding gap locks across
     * the send: {@code WHERE published_at IS NULL} range-scans, and under REPEATABLE READ InnoDB
     * gap-locks the scanned range, which blocks <em>inserts</em> into it. Registering a patient
     * inserts into exactly that range, so a slow broker made registrations fail with lock-wait
     * timeouts — the business transaction depending on the broker, which is the one thing an outbox
     * exists to prevent. Now the claim commits in microseconds and the send happens outside any
     * transaction.
     *
     * <p>Reclaims rows whose lease has expired, which is how a crashed relay's work is recovered.
     * Re-publishing is safe: delivery is at-least-once by design and consumers are idempotent on
     * {@code eventId}. Backed by {@code idx_outbox_claimable}.
     */
    @Modifying
    @Query(value = "UPDATE outbox_events SET claimed_until = :leaseUntil, claim_token = :token "
            + "WHERE published_at IS NULL AND (claimed_until IS NULL OR claimed_until <= :now) "
            + "ORDER BY created_at ASC LIMIT :limit", nativeQuery = true)
    int claimBatch(@Param("token") String token, @Param("leaseUntil") Instant leaseUntil,
            @Param("now") Instant now, @Param("limit") int limit);

    /** The rows won by one {@link #claimBatch} call, oldest first — a point lookup on the token. */
    @Query(value = "SELECT * FROM outbox_events WHERE claim_token = :token ORDER BY created_at ASC",
            nativeQuery = true)
    List<OutboxEvent> findByClaimToken(@Param("token") String token);

    /**
     * Deletes one bounded batch of already-published rows older than {@code cutoff}, oldest first.
     *
     * <p><b>Only published rows.</b> {@code published_at IS NOT NULL} is the safety predicate — an
     * unpublished row is still pending work, and deleting it would silently drop an event.
     *
     * <p><b>Deliberately batched, not one big DELETE.</b> A single unbounded delete would hold row
     * locks for its whole duration, balloon the undo log, and — now that a replica exists — produce
     * one enormous binlog transaction the replica must replay before it can apply anything else,
     * spiking replication lag. Small batches keep locks short and replication smooth.
     *
     * <p>Native because MariaDB's {@code DELETE ... ORDER BY ... LIMIT} has no JPQL equivalent. The
     * predicate seeks on the leading column of {@code idx_outbox_unpublished (published_at, ...)}, so
     * it's a range scan rather than a table scan. {@code @Transactional} sits here so each batch
     * commits on its own.
     */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM outbox_events WHERE published_at IS NOT NULL AND published_at < :cutoff "
            + "ORDER BY published_at ASC LIMIT :limit", nativeQuery = true)
    int deletePublishedBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
