package com.pm.patientservice.cache;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * A best-effort distributed lock over Redis ({@code SET key token NX PX}), used only to collapse a
 * cross-instance cache stampede onto a single DB load.
 *
 * <p><b>Deliberately not Redlock.</b> This lock guards an <em>optimization</em> (don't let N replicas
 * reload the same cold key at once), not correctness — no money or state depends on mutual exclusion
 * here. If the lock is ever wrongly held or lost (a holder dies, the TTL lapses mid-load, a Redis
 * failover drops the key), the only consequence is a few redundant reads of the same immutable row.
 * That benign failure mode is exactly why a single-node {@code SET NX PX} is the right tool, and the
 * multi-node Redlock ceremony (with its well-known correctness caveats) would be over-engineering —
 * Kleppmann's "locks for efficiency vs. correctness" distinction: this is efficiency.
 *
 * <p><b>Fail-open.</b> Every operation swallows Redis errors — a lock we can't take is reported as
 * "not acquired", never an exception, so the caller degrades to loading from the DB. The cache (and
 * this lock on top of it) is never a hard dependency of a read.
 *
 * <p><b>Safe release.</b> Release is a compare-and-delete Lua script keyed on our unique token, so a
 * caller whose lock already expired can never delete the <em>next</em> holder's lock.
 *
 * <p>Only present when caching is on ({@code spring.cache.type=redis}); with caching off (tests) the
 * bean is absent and the reader skips the lock path entirely.
 */
@Component
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = true)
@RequiredArgsConstructor
public class RedisDistributedLock {

    private static final Logger log = LoggerFactory.getLogger(RedisDistributedLock.class);

    // Delete the key only if it still holds our token — never someone else's (compare-and-delete).
    private static final RedisScript<Long> RELEASE = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    /**
     * Try to take the lock for {@code ttl}. Returns the fencing token if acquired, or empty if the
     * lock is held elsewhere <em>or</em> Redis is unavailable (fail-open — the caller then just loads).
     */
    public Optional<String> tryAcquire(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(key, token, ttl);
            return Boolean.TRUE.equals(acquired) ? Optional.of(token) : Optional.empty();
        } catch (RuntimeException ex) {
            log.warn("Distributed lock acquire failed [{}] — proceeding without lock: {}", key, ex.toString());
            return Optional.empty();
        }
    }

    /** Release the lock iff we still own it (token match). A no-op if it already expired or was lost. */
    public void release(String key, String token) {
        try {
            redis.execute(RELEASE, List.of(key), token);
        } catch (RuntimeException ex) {
            log.warn("Distributed lock release failed [{}] — it will expire on its own TTL: {}", key, ex.toString());
        }
    }
}
