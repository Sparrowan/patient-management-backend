package com.pm.billingservice.exception;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Signals that an account cannot be closed because it still holds funds — a patient with a non-zero
 * balance may not be deleted until it is settled. It is raised on the synchronous deletion-veto path
 * ({@code CloseAccountForPatient} gRPC), which maps it to gRPC {@code FAILED_PRECONDITION};
 * patient-service turns that into a 409. It never surfaces on a billing REST endpoint, so it has no
 * HTTP mapping here.
 */
public class AccountHasBalanceException extends RuntimeException {

    public AccountHasBalanceException(UUID accountId, BigDecimal balance) {
        super("Account " + accountId + " cannot be closed: balance is " + balance + " (must be zero)");
    }
}
