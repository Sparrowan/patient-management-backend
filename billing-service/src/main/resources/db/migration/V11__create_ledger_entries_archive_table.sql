-- The cold half of the ledger. `ledger_entries` stays hot (recent movements, read constantly);
-- entries older than the retention window move here so the hot table's indexes stay small.
--
-- WHY A SECOND TABLE RATHER THAN PARTITIONING `ledger_entries`: a partitioned table cannot keep a
-- foreign key (MariaDB ERROR 1217) and forces every unique key to contain the partition column
-- (ERROR 1503) -- which silently turns `UNIQUE (idempotency_key)` into *per-partition* uniqueness
-- and lets the same key insert twice, i.e. a double-payment hole. See ROADMAP. Two plain tables
-- keep BOTH constraints intact on BOTH halves.
--
-- The price, stated honestly: global uniqueness is no longer one constraint. It is now
-- (hot UNIQUE) AND (archive UNIQUE) AND the move being atomic -- so no key can be in both tables
-- or in neither, and every lookup must read both. The read order is what makes that safe; see
-- LedgerEntryLookup.
--
-- Column list mirrors `ledger_entries` exactly so the move is a plain `INSERT ... SELECT` with no
-- transform. Rows arrive by SQL and never through JPA: created_at/created_by have to survive the
-- move verbatim, and a JPA insert would re-stamp them via @CreatedDate/@CreatedBy with the time of
-- the *move*, rewriting who moved the money and when.
CREATE TABLE ledger_entries_archive (
    id              UUID          NOT NULL,
    account_id      UUID          NOT NULL,
    type            VARCHAR(10)   NOT NULL,
    amount          DECIMAL(19,2) NOT NULL,
    balance_after   DECIMAL(19,2) NOT NULL,
    idempotency_key VARCHAR(100)  NOT NULL,
    description     VARCHAR(255)  NULL,
    transfer_id     UUID          NULL,
    payout_id       UUID          NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,
    created_by      VARCHAR(100)  NULL,
    updated_by      VARCHAR(100)  NULL,
    version         BIGINT        NOT NULL,
    -- When the row was moved. Not part of the ledger record itself -- it audits the archiving
    -- process, so "why is this entry cold?" is answerable without reading scheduler logs.
    archived_at     DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_ledger_archive_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_ledger_archive_account FOREIGN KEY (account_id) REFERENCES billing_accounts (id),
    -- Backs the cold half of a per-account history read, in the same (account, time, id) order the
    -- hot table is read in, so paging across the seam needs no re-sort.
    INDEX idx_ledger_archive_account (account_id, created_at, id)
) ENGINE=InnoDB;

-- The archiver selects its batch by age (`created_at < cutoff`). Without this index that is a full
-- scan of the hot table on every sweep -- the same lesson as `idx_outbox_unpublished`: a background
-- job that scans the table it is trying to keep small defeats its own purpose.
CREATE INDEX idx_ledger_created_at ON ledger_entries (created_at);
