package com.pm.billingservice.idempotency;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic trigger for the idempotency TTL sweep. Kept separate from {@link IdempotencyRetentionWorker}
 * so the worker's logic is testable without the scheduler firing in the background.
 *
 * <p>Gated by {@code idempotency.retention.enabled} (default true) so tests can turn it off; the sweep
 * interval defaults to hourly (expired rows are not urgent — the TTL already stops them being served).
 */
@Component
@ConditionalOnProperty(prefix = "idempotency.retention", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class IdempotencyRetentionScheduler {

    private final IdempotencyRetentionWorker worker;

    @Scheduled(fixedDelayString = "${idempotency.retention.sweep-interval-ms:3600000}")
    public void tick() {
        worker.sweepExpired();
    }
}
