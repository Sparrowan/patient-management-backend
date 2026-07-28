package com.pm.billingservice.exception;

import java.math.BigDecimal;
import java.util.UUID;

/** Thrown when a debit would overdraw an account. Mapped to HTTP 422 by the handler. */
public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(UUID accountId, BigDecimal balance, BigDecimal requested) {
        super("Account " + accountId + " has insufficient funds: balance " + balance
                + ", requested " + requested);
    }
}
