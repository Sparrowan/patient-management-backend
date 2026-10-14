-- A claim lease for the outbox relay, so the Kafka send can move OUT of the claiming transaction.
--
-- THE DEFECT THIS FIXES. The relay claimed its batch with
--   SELECT ... WHERE published_at IS NULL ... FOR UPDATE SKIP LOCKED
-- and held that claim for the whole transaction, Kafka send included. SKIP LOCKED only spares other
-- readers taking the same lock; under REPEATABLE READ the range scan also takes GAP locks, which
-- block INSERTS into the range -- and inserting into that range is exactly what registering a
-- patient does. So a slow or unreachable broker made every send burn its timeout holding those gaps,
-- and POST /patients failed with "Lock wait timeout exceeded". Measured at ~6s to block, then time
-- out. That inverts the whole point of an outbox: the business transaction must not depend on the
-- broker.
--
-- WHY A LEASE AND NOT JUST A SHORTER LOCK. Once the send is outside the transaction, the lock is
-- gone by the time we publish -- so nothing stops a second relay re-claiming the same rows and
-- double-publishing. The lock WAS the claim. A lease replaces it with durable state: a row claimed
-- until T is invisible to other relays until T passes, whether or not the claimer is still alive.
-- Same pattern (and the same reasoning) as the 60s claim lease on idempotency_keys.
--
-- A dead relay therefore parks rows for at most the lease, after which they are retried -- which is
-- safe because delivery is at-least-once by design and consumers are idempotent on eventId.
ALTER TABLE outbox_events
    ADD COLUMN claimed_until DATETIME(6) NULL AFTER attempts,
    -- Identifies the rows one claim attempt won, without needing a SELECT ... FOR UPDATE first:
    -- the UPDATE stamps a fresh token, then the relay reads back exactly that token's rows.
    ADD COLUMN claim_token   VARCHAR(36) NULL AFTER claimed_until;

-- The relay's claim predicate is now "unpublished AND (unclaimed OR lease expired), oldest first".
-- Leading published_at still seeks the NULLs cheaply; claimed_until narrows within them. Without
-- this, the claiming UPDATE filesorts and over-locks, which is the same mistake
-- idx_outbox_unpublished was created to avoid.
CREATE INDEX idx_outbox_claimable ON outbox_events (published_at, claimed_until, created_at);

-- Reading back one claim's rows is a point lookup on the token, not a scan.
CREATE INDEX idx_outbox_claim_token ON outbox_events (claim_token);
