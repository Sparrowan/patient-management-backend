-- Transactional Outbox. A "PatientRegistered" event is written here in the SAME transaction as the
-- patient insert (atomic — the intent can never be lost), then the OutboxRelay ships unpublished
-- rows to Kafka and stamps published_at. This is the reliable replacement for the old best-effort
-- gRPC call. Append-mostly: rows are inserted, then updated once (published_at/attempts).
CREATE TABLE outbox_events (
    id             UUID          NOT NULL,
    aggregate_type VARCHAR(64)   NOT NULL,           -- e.g. 'Patient'
    aggregate_id   UUID          NOT NULL,           -- the patient id (Kafka partition key)
    event_type     VARCHAR(64)   NOT NULL,           -- e.g. 'PatientRegistered'
    topic          VARCHAR(128)  NOT NULL,           -- destination Kafka topic
    -- Holds a small JSON document (the event fields). Kept VARCHAR (not native JSON) so it maps
    -- cleanly to a String field under ddl-auto=validate; the relay parses it and emits Avro.
    payload        VARCHAR(2048) NOT NULL,
    created_at     DATETIME(6)   NOT NULL,
    published_at   DATETIME(6)   NULL,               -- NULL = not yet published
    attempts       INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- The relay polls "unpublished, oldest first"; leading published_at lets it seek NULLs cheaply.
    INDEX idx_outbox_unpublished (published_at, created_at)
) ENGINE=InnoDB;
