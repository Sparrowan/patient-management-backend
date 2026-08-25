-- Distributed-tracing continuity for the outbox. The Kafka publish happens later, on the relay's
-- @Scheduled thread, so without help its span would root at the relay tick rather than the request
-- that wrote this row. We capture the W3C traceparent (00-<trace>-<span>-<flags>, ~55 chars) at
-- write time; the relay restores it before the send so publish→consume joins the originating trace.
-- Nullable: rows written before this column existed, or outside any active trace, simply publish
-- untraced (the relay falls back to its own context).
ALTER TABLE outbox_events ADD COLUMN trace_parent VARCHAR(64) NULL AFTER payload;
