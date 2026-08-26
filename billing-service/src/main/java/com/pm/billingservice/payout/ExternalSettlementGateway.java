package com.pm.billingservice.payout;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The boundary the payout saga cannot span with a database transaction: the external settlement rail
 * (another bank / a payment provider). Implementations must be <b>idempotent on {@code payoutId}</b>
 * — the worker may retry after an ambiguous timeout, and a retry must never send the money twice.
 *
 * <p>In production this is a real HTTP/gRPC call to the provider; here it's a {@link
 * SimulatedExternalSettlementGateway}. The saga's mechanics (durable state, retries, compensation)
 * are identical either way — only this one call changes.
 */
public interface ExternalSettlementGateway {

    /**
     * Requests settlement of {@code amount} to {@code destinationReference}. Returns the outcome
     * rather than throwing for business failures, so the worker can branch on it; only genuinely
     * unexpected infrastructure faults surface as exceptions.
     */
    SettlementOutcome settle(UUID payoutId, String destinationReference, BigDecimal amount, String currency);
}
