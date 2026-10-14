package com.pm.patientservice.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic trigger for {@link OutboxRetentionWorker}. Kept separate from the worker so tests can drive
 * the pruning logic directly instead of racing a background tick.
 *
 * <p>Hourly by default — expired rows are not urgent, and a slow cadence keeps the delete load off the
 * primary (and the replication stream) at a trickle rather than in bursts. Gated by
 * {@code outbox.retention.enabled} so it can be turned off entirely.
 */
@Component
@ConditionalOnProperty(prefix = "outbox.retention", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class OutboxRetentionScheduler {

    private final OutboxRetentionWorker worker;

    /**
     * <b>Why an initial delay.</b> {@code fixedDelay} with no initial delay runs the moment the
     * context starts, so a rolling deploy of N replicas fires N sweeps at once — and start-up is
     * precisely when the service is busiest filling caches and pools. The work is safe to duplicate
     * (claimed or idempotent), so this is not a correctness fix; it keeps a deploy from coinciding
     * with the heaviest background job in the service. Pollers deliberately get none: delaying them
     * delays the work itself.
     */
    @Scheduled(fixedDelayString = "${outbox.retention.sweep-interval-ms:3600000}",
            initialDelayString = "${outbox.retention.initial-delay-ms:120000}")
    public void tick() {
        worker.purgeExpired();
    }
}
