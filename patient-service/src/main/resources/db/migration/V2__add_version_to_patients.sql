-- Optimistic-locking version column for the Patient entity's @Version field.
-- Existing rows default to 0; Hibernate increments on each update.
ALTER TABLE patients ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
