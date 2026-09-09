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

    @Scheduled(fixedDelayString = "${outbox.retention.sweep-interval-ms:3600000}")
    public void tick() {
        worker.purgeExpired();
    }
}
