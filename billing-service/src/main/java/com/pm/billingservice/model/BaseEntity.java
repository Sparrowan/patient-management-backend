package com.pm.billingservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Cross-cutting persistence fields every entity carries: audit timestamps (populated by Spring
 * Data JPA auditing) and the optimistic-lock version. {@code createdBy}/{@code updatedBy} come
 * once auth supplies a principal via {@code AuditorAware}.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
public abstract class BaseEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic-lock version. Managed by JPA; bumped on every update to detect lost updates. */
    @Version
    private long version;
}
