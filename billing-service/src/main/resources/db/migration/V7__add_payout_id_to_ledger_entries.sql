-- Link a payout's ledger legs back to the payout (mirrors transfer_id), so the audit trail for a
-- payout — the initial DEBIT now, a compensating CREDIT later on reversal — is queryable as one
-- thing. Nullable: plain credits/debits and transfer legs leave it null.
ALTER TABLE ledger_entries ADD COLUMN payout_id UUID NULL AFTER transfer_id;
