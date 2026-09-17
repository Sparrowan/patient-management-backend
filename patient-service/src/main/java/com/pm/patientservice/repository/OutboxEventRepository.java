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
     * The next batch to publish: unpublished rows, oldest first, capped so one poll can't load an
     * unbounded backlog. Backed by {@code idx_outbox_unpublished (published_at, created_at)}.
     *
     * <p><b>Multi-instance safe</b> via a native {@code FOR UPDATE SKIP LOCKED}: relays on different
     * replicas each claim a <em>disjoint</em> batch — a second relay <b>skips</b> the rows the first
     * has locked rather than blocking on them or double-publishing them. The locks are held until the
     * relay's {@code @Transactional} commits (after the Kafka send + the {@code publishedAt} stamp), so
     * a claimed row is invisible to every other relay for its whole in-flight window.
     *
     * <p>Native (not JPQL {@code @Lock}) because {@code SKIP LOCKED} + a row limit is a database-native
     * concern — the explicit SQL is unambiguous and portable across the small set of engines we target,
     * where the JPA {@code lock.timeout=-2} hint combined with {@code Pageable} did not apply the limit
     * correctly.
     */
    @Query(value = "SELECT * FROM outbox_events WHERE published_at IS NULL "
            + "ORDER BY created_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> lockUnpublishedBatch(@Param("limit") int limit);

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
