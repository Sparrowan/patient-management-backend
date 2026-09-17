package com.pm.billingservice.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One money movement, regardless of which half of the ledger currently stores it.
 *
 * <p>{@link LedgerEntry} (hot) and {@link ArchivedLedgerEntry} (cold) are the same record in two
 * storage locations, not two concepts — archiving relocates a row, it does not transform it. This
 * interface is what lets callers that only <em>read</em> a movement (idempotency replay, account
 * history) stay indifferent to where it lives, while the two classes keep their very different
 * write semantics: one is a rich append-only entity with a domain factory, the other is
 * {@code @Immutable} and written only by SQL.
 *
 * <p>Sealed because those two are the only storage locations there will ever be; a third would be a
 * design decision, not an extension point.
 */
public sealed interface LedgerRecord permits LedgerEntry, ArchivedLedgerEntry {

    UUID getId();

    UUID getAccountId();

    EntryType getType();

    BigDecimal getAmount();

    BigDecimal getBalanceAfter();

    String getIdempotencyKey();

    String getDescription();

    Instant getCreatedAt();
}
