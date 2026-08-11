package com.pm.patientservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;

/**
 * Opt-in base for entities that are soft-deleted rather than physically removed. A single
 * nullable {@code deletedAt} is the whole state — {@code null} means live, non-null means deleted
 * (no separate boolean flag to keep in sync). Concrete entities must still add
 * {@code @SQLRestriction("deleted_at is null")} themselves — Hibernate applies that filter
 * per-entity, not through a mapped superclass. Append-only entities (e.g. a ledger) deliberately
 * do <em>not</em> extend this.
 */
@MappedSuperclass
@Getter
public abstract class SoftDeletableEntity extends BaseEntity {

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** True once soft-deleted — derived from the timestamp. */
    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** Soft-deletes the entity, stamping when it happened. */
    public void markDeleted() {
        this.deletedAt = Instant.now();
    }

    /** Reverses a soft delete (compensating action) — clears the timestamp so the row is live again. */
    public void restore() {
        this.deletedAt = null;
    }
}
