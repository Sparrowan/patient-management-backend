package com.pm.billingservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;

/**
 * An immutable, append-only record of one money movement on an account — the audit trail.
 * Entries are never updated or deleted (so this extends {@link BaseEntity}, not
 * {@code SoftDeletableEntity}). Each entry captures the {@code balanceAfter} at the time, so an
 * idempotent replay can return the original result regardless of later movements. The
 * {@code idempotencyKey} is unique, which is the hard guarantee against double-applying a
 * retried request.
 */
@Entity
@Table(name = "ledger_entries")
@Getter
public class LedgerEntry extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EntryType type;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Column(length = 255)
    private String description;

    /** Set only on the two legs of a {@link Transfer}, linking them; null for a plain credit/debit. */
    @Column
    private UUID transferId;

    /** Set only on a {@link Payout}'s legs (the DEBIT, and a compensating CREDIT on reversal); else null. */
    @Column
    private UUID payoutId;

    protected LedgerEntry() {
    }

    private LedgerEntry(
            UUID accountId,
            EntryType type,
            BigDecimal amount,
            BigDecimal balanceAfter,
            String idempotencyKey,
            String description) {
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.idempotencyKey = idempotencyKey;
        this.description = description;
    }

    /** Records a movement. The caller applies the balance change to the account first. */
    public static LedgerEntry record(
            UUID accountId,
            EntryType type,
            BigDecimal amount,
            BigDecimal balanceAfter,
            String idempotencyKey,
            String description) {
        return new LedgerEntry(accountId, type, amount, balanceAfter, idempotencyKey, description);
    }

    /** Records one leg of a transfer — same as {@link #record} but tagged with the {@code transferId}. */
    public static LedgerEntry transferLeg(
            UUID accountId,
            EntryType type,
            BigDecimal amount,
            BigDecimal balanceAfter,
            String idempotencyKey,
            String description,
            UUID transferId) {
        LedgerEntry entry = new LedgerEntry(accountId, type, amount, balanceAfter, idempotencyKey, description);
        entry.transferId = transferId;
        return entry;
    }

    /** Records one leg of a payout — same as {@link #record} but tagged with the {@code payoutId}. */
    public static LedgerEntry payoutLeg(
            UUID accountId,
            EntryType type,
            BigDecimal amount,
            BigDecimal balanceAfter,
            String idempotencyKey,
            String description,
            UUID payoutId) {
        LedgerEntry entry = new LedgerEntry(accountId, type, amount, balanceAfter, idempotencyKey, description);
        entry.payoutId = payoutId;
        return entry;
    }
}
