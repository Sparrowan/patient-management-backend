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
 * A money transfer between two accounts, as a first-class record. Modeled as an aggregate (its own
 * table) rather than just two ledger entries so a transfer is queryable, auditable, and reversible
 * as one thing, and so idempotency is enforced at the transfer level by the unique
 * {@code idempotencyKey}. The two accounts are held as <b>ID references</b> (not JPA associations)
 * — the same cross-aggregate rule as elsewhere: no lazy navigation, no N+1.
 *
 * <p>The actual money movement (balance changes) and the double-entry {@link LedgerEntry} legs are
 * written in the same transaction as this row, so either the whole transfer commits or none of it does.
 */
@Entity
@Table(name = "transfers")
@Getter
public class Transfer extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID fromAccountId;

    @Column(nullable = false)
    private UUID toAccountId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status;

    @Column(nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Column(length = 255)
    private String description;

    protected Transfer() {
    }

    private Transfer(
            UUID fromAccountId,
            UUID toAccountId,
            BigDecimal amount,
            String currency,
            String idempotencyKey,
            String description) {
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.amount = amount;
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
        this.description = description;
        this.status = TransferStatus.COMPLETED;
    }

    /**
     * Records a completed transfer. The caller has already applied the balance changes and written
     * the ledger legs in the same transaction — this is the transfer's own audit record.
     */
    public static Transfer record(
            UUID fromAccountId,
            UUID toAccountId,
            BigDecimal amount,
            String currency,
            String idempotencyKey,
            String description) {
        return new Transfer(fromAccountId, toAccountId, amount, currency, idempotencyKey, description);
    }
}
