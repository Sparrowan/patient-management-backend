package com.pm.billingservice.dto;

import java.util.List;

/**
 * A keyset-paginated slice of results. Deliberately <b>not</b> a {@link PagedResponse}: there is no
 * {@code totalElements} / {@code totalPages} / page number, because keyset pagination trades those
 * away — counting a large table on every page (and supporting random "jump to page N" access) is
 * exactly the O(n) cost keyset exists to avoid. A client pages forward by echoing {@link #nextCursor}
 * until {@link #hasMore} is false.
 *
 * @param items      this page's rows, in sort order
 * @param nextCursor opaque token to fetch the next page; {@code null} on the last page
 * @param hasMore    whether another page exists after this one
 */
public record CursorPage<T>(List<T> items, String nextCursor, boolean hasMore) {

    public static <T> CursorPage<T> of(List<T> items, String nextCursor, boolean hasMore) {
        return new CursorPage<>(items, nextCursor, hasMore);
    }
}
