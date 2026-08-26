package com.pm.billingservice.model;

/**
 * Lifecycle of an external payout — the state a saga coordinator drives it through. Unlike an
 * internal {@link Transfer} (one local ACID transaction, only ever {@link TransferStatus#COMPLETED}),
 * a payout crosses a boundary that can't be spanned by a transaction (an external settlement rail),
 * so it has a genuinely durable in-flight state and terminal outcomes reached asynchronously:
 *
 * <ul>
 *   <li>{@link #PENDING} — accepted; the source account has been debited locally and the external
 *       settlement is in flight (or not yet attempted).</li>
 *   <li>{@link #COMPLETED} — the external rail confirmed the settlement. Terminal.</li>
 *   <li>{@link #REVERSED} — settlement failed and was <em>compensated</em>: the debit was credited
 *       back to the source account. Terminal.</li>
 *   <li>{@link #FAILED} — the saga could not reach a clean terminal state after exhausting retries
 *       (e.g. the external status stays ambiguous); parked for operator intervention. Terminal.</li>
 * </ul>
 */
public enum PayoutStatus {
    PENDING,
    COMPLETED,
    REVERSED,
    FAILED
}
