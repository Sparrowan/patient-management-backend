package com.pm.billingservice.payout;

/**
 * The result of asking the external rail to settle a payout.
 *
 * <ul>
 *   <li>{@link #SETTLED} — the money reached the destination. Terminal success.</li>
 *   <li>{@link #DECLINED} — the rail permanently rejected it (bad account, sanctions, etc.). Retrying
 *       won't help — compensate.</li>
 *   <li>{@link #TRANSIENT_ERROR} — a temporary problem (timeout, 5xx). Retry with backoff; the call is
 *       idempotent on the payout id, so a retry after an ambiguous timeout won't double-send.</li>
 * </ul>
 */
public enum SettlementOutcome {
    SETTLED,
    DECLINED,
    TRANSIENT_ERROR
}
