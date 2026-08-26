-- Saga-coordinator bookkeeping for the async settlement worker: how many settlement attempts have
-- been made, when the next attempt is due (drives retry backoff), and why the last attempt failed.
-- next_attempt_at defaults to now so a payout is due for settlement immediately after initiate;
-- existing PENDING rows likewise become due at once.
ALTER TABLE payouts
    ADD COLUMN attempts        INT          NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD COLUMN failure_reason  VARCHAR(255) NULL;

-- The worker claims "PENDING rows due now, oldest first" with FOR UPDATE SKIP LOCKED. This composite
-- index (mirrors idx_outbox_unpublished) makes that a range scan, so the DB locks only the rows it
-- returns instead of filesorting and over-locking the batch — the correctness basis for SKIP LOCKED.
CREATE INDEX idx_payouts_due ON payouts (status, next_attempt_at);
