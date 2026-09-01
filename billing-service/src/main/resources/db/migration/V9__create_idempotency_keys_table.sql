-- Generic HTTP-level idempotency store. Unlike the per-aggregate idempotency_key columns
-- (ledger_entries / transfers / payouts), this is a cross-cutting record of a whole HTTP
-- request+response, keyed by the client's Idempotency-Key so a retried POST replays the
-- ORIGINAL response instead of re-executing. It complements — does not replace — the domain
-- unique-key idempotency, which stays the correctness backstop for money movement.
--
-- Concurrency is handled by claim-on-insert: the (user_sub, id_key) unique constraint lets the
-- first request win an IN_PROGRESS row while a concurrent duplicate collides (→ handled as
-- in-flight/replay). request_fingerprint (a SHA-256 of method+path+body) detects a key reused
-- with a DIFFERENT request. The response_* columns hold what we replay; they are null until the
-- request completes. This is an INFRASTRUCTURE table — no BaseEntity audit/version columns (no
-- business auditing, and the IN_PROGRESS→COMPLETED update is single-writer, so no @Version).
-- InnoDB for the atomic unique-constraint claim. expires_at drives TTL cleanup (24h window).
CREATE TABLE idempotency_keys (
    id                    UUID          NOT NULL,
    id_key                VARCHAR(100)  NOT NULL,
    user_sub              VARCHAR(100)  NOT NULL,
    request_method        VARCHAR(10)   NOT NULL,
    request_path          VARCHAR(255)  NOT NULL,
    request_fingerprint   VARCHAR(64)   NOT NULL,          -- SHA-256 hex of method+path+body
    status                VARCHAR(20)   NOT NULL,          -- IN_PROGRESS | COMPLETED
    response_status       INT,
    response_body         TEXT,
    response_content_type VARCHAR(100),
    created_at            DATETIME(6)   NOT NULL,
    expires_at            DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_idempotency_user_key UNIQUE (user_sub, id_key),
    INDEX idx_idempotency_expires_at (expires_at)
) ENGINE=InnoDB;
