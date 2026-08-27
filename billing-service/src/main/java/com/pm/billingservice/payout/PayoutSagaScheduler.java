package com.pm.billingservice.payout;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Periodic trigger for the payout saga: on a fixed schedule it asks {@link PayoutSagaWorker} to
 * drive the next batch of due PENDING payouts. Kept separate from the worker so the worker's logic
 * is directly testable without the scheduler firing in the background.
 *
 * <p>Gated by {@code payout.saga.enabled} (default true) so tests can turn the periodic trigger off
 * and drive the worker deterministically instead of racing it.
 */
@Component
@ConditionalOnProperty(prefix = "payout.saga", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class PayoutSagaScheduler {

    private final PayoutSagaWorker worker;

    @Scheduled(fixedDelayString = "${payout.saga.poll-interval-ms:2000}")
    public void tick() {
        worker.drivePendingPayouts();
    }
}
