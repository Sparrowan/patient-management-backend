package com.pm.billingservice.payout;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * A stand-in for a real settlement provider so the saga can run end-to-end without external infra.
 * The outcome is driven <b>deterministically by the destination reference</b> (no randomness, so
 * tests and demos are repeatable):
 *
 * <ul>
 *   <li>{@code FAIL-…}  → {@link SettlementOutcome#DECLINED} (permanent — exercises compensation)</li>
 *   <li>{@code RETRY-…} → {@link SettlementOutcome#TRANSIENT_ERROR} (exercises retry/backoff)</li>
 *   <li>anything else   → {@link SettlementOutcome#SETTLED}</li>
 * </ul>
 *
 * <p>Production swaps this for a real idempotent provider call; nothing else in the saga changes.
 */
@Component
public class SimulatedExternalSettlementGateway implements ExternalSettlementGateway {

    @Override
    public SettlementOutcome settle(
            UUID payoutId, String destinationReference, BigDecimal amount, String currency) {
        if (destinationReference.startsWith("FAIL-")) {
            return SettlementOutcome.DECLINED;
        }
        if (destinationReference.startsWith("RETRY-")) {
            return SettlementOutcome.TRANSIENT_ERROR;
        }
        return SettlementOutcome.SETTLED;
    }
}
