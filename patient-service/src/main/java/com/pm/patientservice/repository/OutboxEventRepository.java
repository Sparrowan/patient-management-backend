package com.pm.patientservice.repository;

import com.pm.patientservice.model.OutboxEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for the transactional outbox. The relay reads a bounded, oldest-first batch of
 * not-yet-published rows each poll.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, java.util.UUID> {

    /**
     * The next batch to publish: unpublished rows, oldest first, capped so one slow poll can't load
     * an unbounded backlog. Backed by {@code idx_outbox_unpublished (published_at, created_at)}.
     *
     * <p>Single-relay assumption for now; multi-instance safety (a {@code SELECT ... FOR UPDATE SKIP
     * LOCKED} variant, or ShedLock) is a scale follow-up — see ROADMAP.
     */
    List<OutboxEvent> findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
}
