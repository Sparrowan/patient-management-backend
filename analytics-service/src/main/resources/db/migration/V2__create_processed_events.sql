-- Idempotency ledger for the event consumer.
--
-- Delivery is at-least-once, so the same event can arrive more than once (redelivery after a
-- rebalance, a retry, etc.). A projection that does `count = count + 1` is NOT naturally idempotent,
-- so before applying an event we record its unique eventId here, in the SAME transaction as the
-- projection update. A redelivered eventId is already present → the update is skipped. The primary
-- key is the hard guarantee against double-applying.
CREATE TABLE processed_events (
    event_id     VARCHAR(36) NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id)
) ENGINE = InnoDB;
