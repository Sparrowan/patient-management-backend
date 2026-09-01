package com.pm.billingservice.idempotency;

import com.pm.billingservice.repository.IdempotencyRecordRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes idempotency records past their TTL so the table doesn't grow without bound. A record is
 * only useful while it's replayable (within its {@code expiresAt}); once expired it's dead weight.
 *
 * <p><b>Multi-instance safe by nature:</b> unlike the outbox relay or payout worker, this needs no
 * {@code SKIP LOCKED} claim — the delete is a set operation on already-terminal rows, so N replicas
 * running it concurrently just delete overlapping sets (a second delete of an already-gone row is a
 * no-op). Split from the {@link IdempotencyRetentionScheduler} so the logic is directly testable.
 */
@Component
@RequiredArgsConstructor
public class IdempotencyRetentionWorker {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyRetentionWorker.class);

    private final IdempotencyRecordRepository repository;

    @Transactional
    public int sweepExpired() {
        int deleted = repository.deleteExpired(Instant.now());
        if (deleted > 0) {
            log.info("Idempotency retention: deleted {} expired record(s)", deleted);
        }
        return deleted;
    }
}
