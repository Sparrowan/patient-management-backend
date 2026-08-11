package com.pm.billingservice.exception;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Signals that an account cannot be closed because it still holds funds — a patient with a non-zero
 * balance may not be deleted until it is settled. This is a domain signal consumed by the deletion
 * saga (the {@code PatientEventsConsumer} turns it into a {@code PatientDeletionRejected}
 * compensating event); it is not a REST error, so it has no HTTP mapping.
 */
public class AccountHasBalanceException extends RuntimeException {

    public AccountHasBalanceException(UUID accountId, BigDecimal balance) {
        super("Account " + accountId + " cannot be closed: balance is " + balance + " (must be zero)");
    }
}
