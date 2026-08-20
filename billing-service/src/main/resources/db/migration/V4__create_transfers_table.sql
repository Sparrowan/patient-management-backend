-- Transfers: a first-class record of money moved between two accounts. Money is DECIMAL(19,2)
-- (never float). idempotency_key is unique — the hard guarantee a retried transfer is not applied
-- twice. Accounts are ID references (from_account_id / to_account_id), not FKs to a JPA association.
-- InnoDB for ACID. Native UUID (MariaDB 10.7+). Audit + version columns come from BaseEntity.
CREATE TABLE transfers (
    id               UUID          NOT NULL,
    from_account_id  UUID          NOT NULL,
    to_account_id    UUID          NOT NULL,
    amount           DECIMAL(19,2) NOT NULL,
    currency         VARCHAR(3)    NOT NULL,
    status           VARCHAR(20)   NOT NULL,
    idempotency_key  VARCHAR(100)  NOT NULL,
    description      VARCHAR(255),
    created_at       DATETIME(6)   NOT NULL,
    updated_at       DATETIME(6)   NOT NULL,
    created_by       VARCHAR(100),
    updated_by       VARCHAR(100),
    version          BIGINT        NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_transfers_idempotency_key UNIQUE (idempotency_key),
    INDEX idx_transfers_from (from_account_id),
    INDEX idx_transfers_to (to_account_id)
) ENGINE=InnoDB;
