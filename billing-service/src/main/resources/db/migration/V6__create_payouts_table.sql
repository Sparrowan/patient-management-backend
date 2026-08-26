-- External payouts: money leaving an account to an external settlement rail. Unlike an internal
-- transfer (one local ACID tx), a payout is the root of an orchestrated saga — it persists as
-- PENDING the moment the source is debited, and a coordinator later settles it externally
-- (COMPLETED) or compensates the debit (REVERSED). Money is DECIMAL(19,2). idempotency_key is
-- unique — the hard guarantee a retried request isn't applied twice. source_account_id is an ID
-- reference (not a FK to a JPA association). InnoDB for ACID; audit + version cols from BaseEntity.
CREATE TABLE payouts (
    id                    UUID          NOT NULL,
    source_account_id     UUID          NOT NULL,
    destination_reference VARCHAR(140)  NOT NULL,
    amount                DECIMAL(19,2) NOT NULL,
    currency              VARCHAR(3)    NOT NULL,
    status                VARCHAR(20)   NOT NULL,          -- PENDING | COMPLETED | REVERSED | FAILED
    idempotency_key       VARCHAR(100)  NOT NULL,
    description           VARCHAR(255),
    created_at            DATETIME(6)   NOT NULL,
    updated_at            DATETIME(6)   NOT NULL,
    created_by            VARCHAR(100),
    updated_by            VARCHAR(100),
    version               BIGINT        NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_payouts_idempotency_key UNIQUE (idempotency_key),
    INDEX idx_payouts_source (source_account_id)
) ENGINE=InnoDB;
