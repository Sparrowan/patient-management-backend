-- First read model: registrations rolled up per calendar day.
--
-- This is a *projection*, not a normalized domain table: one pre-aggregated row per day, keyed by
-- the date itself, so a "how many registered on / between dates" query is a single indexed lookup
-- or a small range scan — no COUNT(*) over a growing patients table on the write side. The read
-- side owns this shape precisely because it can denormalize for query speed without the write side
-- caring. The row is disposable: it can be dropped and rebuilt by replaying the event stream.
CREATE TABLE daily_registrations (
    registration_date DATE   NOT NULL,
    registrations     BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (registration_date)
) ENGINE = InnoDB;
