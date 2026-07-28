-- Soft delete marker: NULL = live, non-null = soft-deleted (stamped by markDeleted()).
-- @SQLRestriction("deleted_at is null") filters deleted rows out of every read. A single
-- nullable timestamp is the entire state — no redundant boolean flag. The row (and its unique
-- email) is retained, so emails are not reusable.
ALTER TABLE patients ADD COLUMN deleted_at DATETIME(6) NULL;
