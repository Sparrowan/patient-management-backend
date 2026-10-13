package com.pm.patientservice.outbox;

import com.pm.patientservice.model.OutboxEvent;
import com.pm.patientservice.repository.OutboxEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The outbox relay's three short transactions: claim a batch, then settle each row published or
 * failed. The Kafka send happens between them, in {@link OutboxRelay}, under no transaction at all.
 *
 * <p><b>Its own bean so {@code @Transactional} applies.</b> A self-invoked {@code @Transactional}
 * method does not go through the Spring proxy and silently runs with no transaction — which here
 * would mean a claim that never commits, so every tick re-claims the same rows and publishes them
 * again. Same reason {@code LedgerBatchArchiver} is separate from its worker.
 *
 * <p><b>Why {@code READ_COMMITTED} on the claim.</b> MariaDB defaults to REPEATABLE READ, where a
 * range scan takes <em>gap locks</em> over the range it scanned. The claim's predicate
 * ({@code published_at IS NULL}) is exactly the range a patient registration inserts into, so under
 * the default isolation the claim blocks registrations for as long as it is held. READ COMMITTED
 * takes no gap locks, so the claim can only ever conflict with another relay's claim on the same
 * rows — never with a writer appending new ones. This is the same reason the transfer path runs
 * READ COMMITTED.
 *
 * <p>Belt and braces, then: the claim is short <em>and</em> takes no gap locks. Either alone would
 * mostly do; together the failure mode is closed off rather than narrowed.
 */
@Component
@RequiredArgsConstructor
public class OutboxClaimer {

    /**
     * How long a claim is honoured before another relay may take the row.
     *
     * <p>Sized well above a batch's worth of sends (each bounded by the relay's send timeout) so a
     * live relay is never overtaken mid-flight, and short enough that a relay killed between claim
     * and settle parks its rows for seconds rather than hours. Expiry is the recovery mechanism: the
     * rows are simply re-claimed and re-sent, which is safe because delivery is at-least-once by
     * design and consumers are idempotent on {@code eventId}.
     */
    static final Duration CLAIM_LEASE = Duration.ofMinutes(2);

    private final OutboxEventRepository outboxRepository;

    /**
     * Stamps a lease on up to {@code limit} unclaimed (or expired-claim) unpublished rows and returns
     * them.
     *
     * <p>Two statements, one transaction: the {@code UPDATE} is the atomic claim — two relays racing
     * cannot both win a row, because the loser's predicate no longer matches once the winner has
     * stamped a future {@code claimed_until}. The read-back by token then costs a point lookup. A
     * fresh token per attempt is what makes "the rows I just won" expressible without holding a lock.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<OutboxEvent> claimBatch(int limit) {
        Instant now = Instant.now();
        String token = UUID.randomUUID().toString();
        int claimed = outboxRepository.claimBatch(token, now.plus(CLAIM_LEASE), now, limit);
        return claimed == 0 ? List.of() : outboxRepository.findByClaimToken(token);
    }

    /**
     * Marks one row delivered and drops its claim.
     *
     * <p>Per row, not per batch, and that is deliberate: a batch-wide settle would mean a crash
     * mid-batch re-publishes rows that already made it to the broker. Narrowing the window to a
     * single row keeps at-least-once as tight as it can be without a distributed transaction.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void settlePublished(UUID eventId) {
        outboxRepository.findById(eventId).ifPresent(event -> {
            event.markPublished();
            event.releaseClaim();
        });
    }

    /** Records a failed attempt and releases the claim so the next tick retries without waiting out the lease. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int settleFailed(UUID eventId) {
        return outboxRepository.findById(eventId).map(event -> {
            event.recordFailedAttempt();
            event.releaseClaim();
            return event.getAttempts();
        }).orElse(-1);
    }
}
