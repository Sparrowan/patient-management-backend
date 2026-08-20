-- Link a ledger entry to the transfer it belongs to (double-entry): a transfer writes a DEBIT leg
-- and a matching CREDIT leg, both carrying the same transfer_id. Nullable — plain credit/debit
-- entries have none. Indexed so "the entries for this transfer" is a seek, not a scan.
ALTER TABLE ledger_entries
    ADD COLUMN transfer_id UUID NULL AFTER description;

CREATE INDEX idx_ledger_transfer ON ledger_entries (transfer_id);
