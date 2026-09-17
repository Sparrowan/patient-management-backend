package com.pm.patientservice.config;

import java.util.function.Supplier;

/**
 * Escape hatch for reads that must not be stale.
 *
 * <p>Routing normally sends every {@code @Transactional(readOnly = true)} read to a replica, which is
 * asynchronously behind the primary. That's fine for most reads — but <b>freshness is a per-query
 * requirement, not a per-system setting</b>. Wrap a read in {@link #fromPrimary} when a stale answer
 * would be wrong rather than merely old:
 *
 * <ul>
 *   <li>a read whose result is <em>cached</em> (replica lag of milliseconds becomes cache staleness of
 *       minutes — the lag gets amplified by the TTL),</li>
 *   <li>a read the caller will immediately act on (read-your-own-writes after an update),</li>
 *   <li>anything where a stale {@code @Version} would cause a spurious optimistic-lock conflict.</li>
 * </ul>
 *
 * <p>Implemented as a {@link ThreadLocal} because that is exactly the scope the routing decision is
 * made in — one request, one thread, one connection acquisition. It is always cleared in a
 * {@code finally} so a pooled thread can't leak "force primary" into the next request.
 */
public final class ReadFreshness {

    private static final ThreadLocal<Boolean> FORCE_PRIMARY = new ThreadLocal<>();

    private ReadFreshness() {
    }

    /** Runs {@code read} with routing pinned to the primary, restoring the previous state after. */
    public static <T> T fromPrimary(Supplier<T> read) {
        Boolean previous = FORCE_PRIMARY.get();
        FORCE_PRIMARY.set(Boolean.TRUE);
        try {
            return read.get();
        } finally {
            if (previous == null) {
                FORCE_PRIMARY.remove(); // never leak the flag back into the pooled thread
            } else {
                FORCE_PRIMARY.set(previous);
            }
        }
    }

    static boolean isForcedToPrimary() {
        return Boolean.TRUE.equals(FORCE_PRIMARY.get());
    }
}
