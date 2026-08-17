-- Audit "who": the authenticated principal that created/last-touched a row (from AuditorAware).
-- Nullable — rows written before auth existed, and by non-user actors (Kafka consumer opening an
-- account, schedulers), carry "system"/NULL rather than a username.
ALTER TABLE billing_accounts
    ADD COLUMN created_by VARCHAR(100) NULL AFTER updated_at,
    ADD COLUMN updated_by VARCHAR(100) NULL AFTER created_by;

-- On the append-only ledger, created_by is the audit trail that matters most: *who moved the money*.
-- (Entries are never updated, so updated_by mirrors created_by but is kept for the shared BaseEntity.)
ALTER TABLE ledger_entries
    ADD COLUMN created_by VARCHAR(100) NULL AFTER updated_at,
    ADD COLUMN updated_by VARCHAR(100) NULL AFTER created_by;
