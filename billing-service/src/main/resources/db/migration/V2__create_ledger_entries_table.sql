-- Append-only ledger of money movements. Amounts are DECIMAL(19,2). The unique idempotency_key
-- is the hard guarantee that a retried credit/debit is never applied twice. FK to the account
-- enforces referential integrity; the account_id index backs the per-account ledger query.
CREATE TABLE ledger_entries (
    id              UUID          NOT NULL,
    account_id      UUID          NOT NULL,
    type            VARCHAR(10)   NOT NULL,
    amount          DECIMAL(19,2) NOT NULL,
    balance_after   DECIMAL(19,2) NOT NULL,
    idempotency_key VARCHAR(100)  NOT NULL,
    description     VARCHAR(255)  NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,
    version         BIGINT        NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_ledger_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_ledger_account FOREIGN KEY (account_id) REFERENCES billing_accounts (id),
    INDEX idx_ledger_account (account_id)
) ENGINE=InnoDB;
