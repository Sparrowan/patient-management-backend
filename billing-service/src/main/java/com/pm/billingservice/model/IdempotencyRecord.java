package com.pm.billingservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

/**
 * One record in the generic HTTP idempotency store — a whole request+response keyed by the client's
 * {@code Idempotency-Key} (scoped per user), so a retried POST replays the original response instead
 * of re-executing.
 *
 * <p><b>Infrastructure entity, deliberately not a {@link BaseEntity}.</b> It carries no business
 * auditing ({@code created_by}/{@code updated_by} are meaningless for a bookkeeping row) and needs no
 * {@code @Version}: concurrency is resolved by the {@code (user_sub, id_key)} unique constraint
 * (claim-on-insert), not optimistic locking, and the {@code IN_PROGRESS → COMPLETED} update has a
 * single writer (the request that won the claim). So it stamps its own {@code createdAt}/{@code
 * expiresAt} in the factory rather than via JPA auditing.
 *
 * <p><b>Lifecycle:</b> {@link #start} claims the row {@code IN_PROGRESS} with a request fingerprint
 * and a TTL; {@link #complete} captures the response and flips it to {@code COMPLETED}. A record is
 * only replayable once {@link #isCompleted()} and not {@link #isExpired(Instant)}.
 */
@Entity
@Table(name = "idempotency_keys")
@Getter
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The client-supplied {@code Idempotency-Key} header value. Unique per {@link #userSub}. */
    @Column(nullable = false, length = 100)
    private String idKey;

    /** The authenticated principal ({@code sub}) — scopes the key so tenants can't collide. */
    @Column(nullable = false, length = 100)
    private String userSub;

    @Column(nullable = false, length = 10)
    private String requestMethod;

    @Column(nullable = false, length = 255)
    private String requestPath;

    /** SHA-256 hex of method+path+body — reusing a key with a different request is a client error. */
    @Column(nullable = false, length = 64)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdempotencyStatus status;

    /** Captured response — null until {@link #complete} runs. */
    @Column
    private Integer responseStatus;

    @Column(columnDefinition = "TEXT")
    private String responseBody;

    @Column(length = 100)
    private String responseContentType;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    protected IdempotencyRecord() {
    }

    private IdempotencyRecord(
            String idKey, String userSub, String requestMethod, String requestPath,
            String requestFingerprint, Instant createdAt, Instant expiresAt) {
        this.idKey = idKey;
        this.userSub = userSub;
        this.requestMethod = requestMethod;
        this.requestPath = requestPath;
        this.requestFingerprint = requestFingerprint;
        this.status = IdempotencyStatus.IN_PROGRESS;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /**
     * Claims a new idempotency record in {@code IN_PROGRESS} for the given key/user, stamping the
     * creation time and a TTL (the row is replayable only until {@code expiresAt}).
     */
    public static IdempotencyRecord start(
            String idKey, String userSub, String requestMethod, String requestPath,
            String requestFingerprint, Instant now, java.time.Duration ttl) {
        return new IdempotencyRecord(
                idKey, userSub, requestMethod, requestPath, requestFingerprint, now, now.plus(ttl));
    }

    /** Captures the response and marks the record {@code COMPLETED} — the replayable terminal state. */
    public void complete(int responseStatus, String responseBody, String responseContentType) {
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.responseContentType = responseContentType;
        this.status = IdempotencyStatus.COMPLETED;
    }

    public boolean isCompleted() {
        return status == IdempotencyStatus.COMPLETED;
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }
}
