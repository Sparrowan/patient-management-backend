package com.pm.billingservice.payout;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.pm.billingservice.exception.BillingAccountNotFoundException;
import com.pm.billingservice.model.BillingAccount;
import com.pm.billingservice.model.EntryType;
import com.pm.billingservice.model.LedgerEntry;
import com.pm.billingservice.model.Payout;
import com.pm.billingservice.repository.BillingAccountRepository;
import com.pm.billingservice.repository.LedgerEntryRepository;
import com.pm.billingservice.repository.PayoutRepository;

import lombok.RequiredArgsConstructor;

/**
 * The orchestrator of the payout saga. Each tick it claims a batch of PENDING payouts that are due
 * and drives each one forward by asking the {@link ExternalSettlementGateway} to settle it:
 *
 * <ul>
 *   <li>{@link SettlementOutcome#SETTLED} → {@link Payout#markCompleted()} — terminal.</li>
 *   <li>{@link SettlementOutcome#TRANSIENT_ERROR} → retry with exponential backoff, up to
 *       {@link #MAX_ATTEMPTS}; on exhaustion the payout is parked as {@code FAILED}.</li>
 *   <li>{@link SettlementOutcome#DECLINED} → <em>compensated</em>: the initiate debit is credited
 *       back to the source account and the payout moves to {@code REVERSED}.</li>
 * </ul>
 *
 * <p><b>Compensation vs. parking — the money-safety distinction.</b> A DECLINE is <em>definitive</em>
 * (the rail rejected it, so nothing left), which makes crediting the debit back safe — that's the
 * saga's rollback (REVERSED). Exhausted transient retries are <em>ambiguous</em>: a lost ack could
 * mean an attempt actually settled, so auto-reversing risks a double-pay. Those park as FAILED for
 * reconciliation (query the rail's real status, then complete or reverse by hand) rather than
 * guessing. The compensating CREDIT re-locks the source account ({@code FOR UPDATE}) exactly like
 * the initiate debit, and its ledger key ({@code <key>:reversal}) makes a re-run a unique-key no-op.
 *
 * <p><b>Multi-instance safe:</b> the batch is claimed with {@code FOR UPDATE SKIP LOCKED}
 * ({@link PayoutRepository#claimDuePending}), so workers on N replicas take disjoint batches. The
 * settlement call runs inside the claim transaction (network I/O under a row lock) — the same
 * deliberate trade-off as the outbox relay: the lock only excludes other <em>workers</em> (which
 * skip, not block), so a modest {@link #BATCH_SIZE} keeps each transaction short. A per-payout
 * {@code try/catch} stops one bad payout from failing the rest of the batch.
 *
 * <p>The periodic trigger is {@link PayoutSagaScheduler}; this class holds only the logic, so it can
 * be driven directly in a test without waiting on (or racing) the scheduler.
 */
@Component
@RequiredArgsConstructor
public class PayoutSagaWorker {

    private static final Logger log = LoggerFactory.getLogger(PayoutSagaWorker.class);
    private static final int BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 5;
    private static final long BASE_BACKOFF_MS = 2_000;
    private static final long MAX_BACKOFF_MS = 60_000;

    private final PayoutRepository payoutRepository;
    private final ExternalSettlementGateway settlementGateway;
    private final BillingAccountRepository accountRepository;
    private final LedgerEntryRepository ledgerRepository;

    @Transactional
    public void drivePendingPayouts() {
        List<Payout> batch = payoutRepository.claimDuePending(Instant.now(), BATCH_SIZE);
        for (Payout payout : batch) {
            try {
                advance(payout);
            } catch (Exception e) {
                // The gateway returns outcomes rather than throwing for business failures, so this is
                // an unexpected fault. Log and leave the payout PENDING; it's retried next tick.
                log.warn("Payout saga step failed for {} (attempt {}): {}",
                        payout.getId(), payout.getAttempts(), e.toString());
            }
        }
    }

    /** Drives one claimed payout one step. The entity is managed, so state changes flush at commit. */
    private void advance(Payout payout) {
        SettlementOutcome outcome = settlementGateway.settle(
                payout.getId(), payout.getDestinationReference(), payout.getAmount(), payout.getCurrency());
        switch (outcome) {
            case SETTLED -> payout.markCompleted();
            case DECLINED -> compensate(payout, "Declined by the settlement rail");
            case TRANSIENT_ERROR -> {
                if (payout.getAttempts() + 1 >= MAX_ATTEMPTS) {
                    payout.markFailed("Exhausted retries after transient settlement errors");
                } else {
                    payout.recordFailedAttempt(
                            "Transient settlement error", Instant.now().plusMillis(backoffMs(payout.getAttempts())));
                }
            }
        }
    }

    /**
     * Rolls back a definitively-failed payout: re-locks the source account and credits the debited
     * amount back (a compensating CREDIT ledger leg), then marks the payout REVERSED. Runs inside the
     * worker's claim transaction, so the credit, the ledger leg and the status change commit together.
     */
    private void compensate(Payout payout, String reason) {
        BillingAccount source = accountRepository.findByIdForUpdate(payout.getSourceAccountId())
                .orElseThrow(() -> new BillingAccountNotFoundException(payout.getSourceAccountId()));

        source.credit(payout.getAmount());
        accountRepository.save(source);

        ledgerRepository.save(LedgerEntry.payoutLeg(source.getId(), EntryType.CREDIT, payout.getAmount(),
                source.getBalance(), payout.getIdempotencyKey() + ":reversal", reason, payout.getId()));

        payout.markReversed(reason);
    }

    /** Exponential backoff: base · 2^attempts, capped. */
    private long backoffMs(int attempts) {
        return Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * (1L << attempts));
    }
}
