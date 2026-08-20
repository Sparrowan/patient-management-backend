package com.pm.billingservice.model;

/**
 * Lifecycle of a transfer. A same-database transfer is atomic, so it only ever persists as
 * {@link #COMPLETED} (a failed attempt rolls back and is never written). The remaining states are a
 * forward-looking seam for when a transfer crosses a boundary it can't span with one transaction
 * (an external settlement rail): {@code PENDING} while in flight, {@code FAILED}/{@code REVERSED}
 * for compensation — at which point the transfer becomes an orchestrated saga, not a local tx.
 */
public enum TransferStatus {
    COMPLETED
}
