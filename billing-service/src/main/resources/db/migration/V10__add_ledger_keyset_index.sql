-- Composite index backing keyset (cursor) pagination of an account's ledger. The query filters by
-- account_id and seeks with (created_at, id) < (:ts, :id) ORDER BY created_at DESC, id DESC — this
-- index lets the DB jump straight to the cursor position and read `limit` rows in order, instead of
-- the plain idx_ledger_account (account_id) which still has to sort/scan within an account. The id
-- tail column makes the ordering total (created_at alone isn't unique), so page boundaries are exact.
CREATE INDEX idx_ledger_account_created_id ON ledger_entries (account_id, created_at, id);
