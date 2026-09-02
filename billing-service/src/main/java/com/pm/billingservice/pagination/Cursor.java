package com.pm.billingservice.pagination;

import java.time.Instant;
import java.util.UUID;

/**
 * A decoded keyset position — the sort key of the last row a client has seen. Paging resumes from
 * "strictly after" this position.
 *
 * <p>It's a <b>tuple</b> ({@code createdAt} + {@code id}), not just the timestamp, because
 * {@code created_at} is not unique (two entries can share a microsecond). The {@code id} tiebreak
 * gives the ordering a <em>total</em> order, which is what guarantees no row is skipped or repeated
 * at a page boundary. Clients never see this directly — {@link CursorCodec} encodes it to an opaque
 * token.
 */
public record Cursor(Instant createdAt, UUID id) {
}
