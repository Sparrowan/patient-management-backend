package com.pm.billingservice.exception;

import java.util.List;

/**
 * A ledger-history request asked for an ordering other than by time.
 *
 * <p>The ledger spans a hot and an archived table split on {@code created_at}; any other ordering
 * would require reading both halves in full and merging them in memory. Rejecting the request is
 * the honest answer — quietly re-sorting or returning a half-sorted page would be worse.
 */
public class UnsupportedLedgerSortException extends RuntimeException {

    public UnsupportedLedgerSortException(List<String> requested) {
        super("Ledger history can only be sorted by 'createdAt', but got: " + String.join(", ", requested));
    }
}
