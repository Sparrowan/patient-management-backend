package com.pm.patientservice.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
