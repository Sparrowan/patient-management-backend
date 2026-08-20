package com.pm.billingservice.exception;

import java.util.UUID;

/**
 * Thrown when a transfer is attempted between accounts in different currencies. There is no FX yet,
 * so the two sides must match. Mapped to HTTP 422 by the handler (a well-formed request that a
 * business rule rejects).
 */
public class CurrencyMismatchException extends RuntimeException {

    public CurrencyMismatchException(UUID fromAccountId, String fromCurrency, UUID toAccountId, String toCurrency) {
        super("Cannot transfer between different currencies: account " + fromAccountId + " is " + fromCurrency
                + ", account " + toAccountId + " is " + toCurrency);
    }
}
