package com.pm.billingservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

/**
 * An <b>external payout</b>: money leaving a billing account to an external settlement rail — a
 * first-class aggregate because, unlike an internal {@link Transfer}, it cannot complete in one
 * local transaction. The debit is local and immediate; the external settlement is a separate,
 * slow, failure-prone boundary. So a payout is the root of an <b>orchestrated saga</b>: it persists
 * as {@link PayoutStatus#PENDING} the moment the source is debited, and a coordinator later drives
 * it to {@link PayoutStatus#COMPLETED} or, on failure, compensates back to {@link PayoutStatus#REVERSED}.
 *
 * <p>The source account is an <b>ID reference</b> (not a JPA association) — the same cross-aggregate
 * rule as {@link Transfer}. Idempotency is enforced at the payout level by the unique
 * {@code idempotencyKey}. Rich model: created via {@link #initiate}, no public setters.
 */
@Entity
@Table(name = "payouts")
@Getter
public class Payout extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID sourceAccountId;

    /** Opaque external destination (e.g. an IBAN / provider account handle). Not PHI. */
    @Column(nullable = false, length = 140)
    private String destinationReference;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PayoutStatus status;

    @Column(nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Column(length = 255)
    private String description;

    /** Settlement attempts made by the saga worker so far. */
    @Column(nullable = false)
    private int attempts;

    /** Earliest time the worker should (re)attempt settlement — moved forward on each retry (backoff). */
    @Column(nullable = false)
    private Instant nextAttemptAt;

    /** Why the last settlement attempt failed; null while healthy. Diagnostic only. */
    @Column(length = 255)
    private String failureReason;

    protected Payout() {
    }

    private Payout(
            UUID sourceAccountId,
            String destinationReference,
            BigDecimal amount,
            String currency,
            String idempotencyKey,
            String description) {
        this.sourceAccountId = sourceAccountId;
        this.destinationReference = destinationReference;
        this.amount = amount;
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
        this.description = description;
        this.status = PayoutStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = Instant.now(); // due for settlement immediately
    }

    /**
     * Starts a payout in {@link PayoutStatus#PENDING}. The caller has already debited the source
     * account and written the DEBIT ledger leg in the same transaction; the external settlement is
     * driven asynchronously by the saga coordinator.
     */
    public static Payout initiate(
            UUID sourceAccountId,
            String destinationReference,
            BigDecimal amount,
            String currency,
            String idempotencyKey,
            String description) {
        return new Payout(sourceAccountId, destinationReference, amount, currency, idempotencyKey, description);
    }

    /** The external rail confirmed settlement — terminal success. */
    public void markCompleted() {
        this.status = PayoutStatus.COMPLETED;
    }

    /**
     * A settlement attempt failed transiently; schedule the next retry. Bumps {@link #attempts} and
     * pushes {@link #nextAttemptAt} out so the worker backs off instead of hot-looping.
     */
    public void recordFailedAttempt(String reason, Instant nextAttemptAt) {
        this.attempts++;
        this.failureReason = reason;
        this.nextAttemptAt = nextAttemptAt;
    }

    /**
     * Compensation succeeded: settlement did not go through, so the debit was credited back to the
     * source account and the payout is terminally {@link PayoutStatus#REVERSED}. Reached only from a
     * <em>definitive</em> non-settlement (the rail declined) — where we know no money left — so
     * crediting back cannot double-refund.
     */
    public void markReversed(String reason) {
        this.status = PayoutStatus.REVERSED;
        this.failureReason = reason;
    }

    /**
     * Parks the payout as terminally {@link PayoutStatus#FAILED} — the outcome is <em>ambiguous</em>
     * (retries exhausted on transient errors; a lost ack may mean the money actually left), so the
     * debit is deliberately <b>not</b> auto-reversed: blindly crediting back could double-pay. It
     * waits for reconciliation (query the rail's real status, then complete or reverse by hand).
     */
    public void markFailed(String reason) {
        this.status = PayoutStatus.FAILED;
        this.failureReason = reason;
    }
}
