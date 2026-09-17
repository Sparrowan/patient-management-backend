package com.pm.billingservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

/**
 * A {@link LedgerEntry} that has aged past the retention window and moved to the cold table. Same
 * money movement, same id, same audit columns — only its storage changed.
 *
 * <p><b>Read-only by construction.</b> Rows arrive through a SQL {@code INSERT ... SELECT} inside
 * the archiving transaction, never through JPA. That is also why this deliberately does <em>not</em>
 * extend {@link BaseEntity}: inheriting {@code @CreatedDate}/{@code @CreatedBy} would let a JPA
 * write re-stamp the audit columns with the time of the <em>move</em>, rewriting who moved the money
 * and when — destroying the very trail the archive exists to preserve. The columns are mapped as
 * plain values instead, and Hibernate's {@code @Immutable} keeps the persistence context from
 * dirty-checking or flushing an update against them.
 *
 * <p>The {@code version} column is likewise a plain value, not {@code @Version}: optimistic locking
 * is meaningless on a table nothing updates, and mapping it as a lock column would invite Hibernate
 * to bump it.
 */
@Entity
@Table(name = "ledger_entries_archive")
@Immutable
@Getter
public final class ArchivedLedgerEntry implements LedgerRecord {

    /** Carried over from the hot row — an entry keeps its identity across the move. */
    @Id
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

    @Column
    private UUID transferId;

    @Column
    private UUID payoutId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column(length = 100)
    private String createdBy;

    @Column(length = 100)
    private String updatedBy;

    @Column(nullable = false)
    private long version;

    /** When the archiver moved this row — audits the archiving process, not the money movement. */
    @Column(nullable = false)
    private Instant archivedAt;

    protected ArchivedLedgerEntry() {
    }
}
