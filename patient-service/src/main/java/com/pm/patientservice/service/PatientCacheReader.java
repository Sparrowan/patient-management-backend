package com.pm.patientservice.service;

import com.pm.patientservice.cache.RedisDistributedLock;
import com.pm.patientservice.config.CacheConfig;
import com.pm.patientservice.dto.PatientResponseDTO;
import com.pm.patientservice.mapper.PatientMapper;
import com.pm.patientservice.repository.PatientRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * The cache-aside read boundary for a single patient — manual cache-aside (not {@code @Cacheable}) so
 * a cold key can be loaded under a <b>cross-instance</b> single-flight lock.
 *
 * <p><b>Why manual, not {@code @Cacheable(sync=true)}?</b> {@code sync=true} is single-flight, but its
 * lock is <em>per JVM</em>: across replicas, one thread per instance still stampedes the DB on a cold
 * key, and a waiter can't read back the winner's value from inside a {@code @Cacheable} body. To get a
 * cluster-wide single load we manage the cache by hand and gate the load with a Redis distributed lock
 * ({@link RedisDistributedLock}). Hits still cost one Redis GET and never open a transaction.
 *
 * <p><b>The flow on a miss:</b> try to take the distributed lock. The winner double-checks the cache
 * (another instance may have populated it in the gap), loads from the DB, writes the cache, releases.
 * A loser waits briefly, polling for the winner's value; if it doesn't appear within
 * {@link #WAIT_BUDGET} it fails open and loads from the DB itself (bounded tail latency beats blocking).
 *
 * <p><b>Negative caching.</b> An absent id caches an empty value (stored as a Redis {@code NullValue}),
 * so repeated lookups of a missing id don't re-hit the DB. The method returns {@link Optional} rather
 * than throwing, because an <em>absence</em> can only be cached if the load returns a value — the
 * service keeps the throw-for-not-found contract, this data layer just makes absence cacheable. It also
 * means the caller crosses a real bean boundary (a self-invocation on the service would bypass caching).
 *
 * <p><b>Fail-open throughout.</b> Every cache and lock operation swallows Redis errors and degrades to
 * the DB — a Redis outage slows reads, it never fails them.
 */
@Component
@RequiredArgsConstructor
public class PatientCacheReader {

    private static final Logger log = LoggerFactory.getLogger(PatientCacheReader.class);

    /** Lock hold time: comfortably longer than a PK lookup, short enough to bound an orphaned lock. */
    private static final Duration LOCK_TTL = Duration.ofSeconds(3);
    /** A loser polls the cache for the winner's value for at most this long before loading itself. */
    private static final Duration WAIT_BUDGET = Duration.ofMillis(250);
    /** Poll interval while waiting for the winner. */
    private static final Duration WAIT_POLL = Duration.ofMillis(40);

    private final PatientRepository patientRepository;
    private final PatientMapper patientMapper;
    private final CacheManager cacheManager;
    // Absent when caching is off (tests: spring.cache.type=none) → the reader just loads from the DB.
    private final Optional<RedisDistributedLock> distributedLock;

    public Optional<PatientResponseDTO> find(UUID id) {
        Cache cache = cacheManager.getCache(CacheConfig.PATIENTS_CACHE);
        if (cache == null || distributedLock.isEmpty()) {
            // Caching off (tests) — straight to the DB, no cache, no lock.
            return loadFromDb(id);
        }

        // 1. Fast path: a warm key (value present, or a negatively-cached absence) returns with no lock.
        Cache.ValueWrapper cached = getQuietly(cache, id);
        if (cached != null) {
            return toOptional(cached);
        }

        // 2. Cold key: collapse the cluster-wide stampede onto one loader.
        return loadWithSingleFlight(cache, id, distributedLock.get());
    }

    private Optional<PatientResponseDTO> loadWithSingleFlight(Cache cache, UUID id, RedisDistributedLock lock) {
        String lockKey = "patient-service::lock::" + CacheConfig.PATIENTS_CACHE + "::" + id;
        Optional<String> token = lock.tryAcquire(lockKey, LOCK_TTL);
        if (token.isPresent()) {
            try {
                // Double-check: another instance may have populated between our miss and acquiring the lock.
                Cache.ValueWrapper now = getQuietly(cache, id);
                if (now != null) {
                    return toOptional(now);
                }
                Optional<PatientResponseDTO> loaded = loadFromDb(id);
                putQuietly(cache, id, loaded.orElse(null)); // null → NullValue → negatively cached
                return loaded;
            } finally {
                lock.release(lockKey, token.get());
            }
        }

        // Lost the race: wait briefly for the winner's value, else fail open and load ourselves.
        Cache.ValueWrapper appeared = awaitCache(cache, id);
        return appeared != null ? toOptional(appeared) : loadFromDb(id);
    }

    private Optional<PatientResponseDTO> loadFromDb(UUID id) {
        // findById excludes soft-deleted rows (@SQLRestriction) → a deleted patient reads as absent
        // and is negatively cached — correct, it's "not found". The repository read runs in its own
        // read-only transaction (Spring Data default), so a cache hit opens no transaction at all and
        // the mapper only touches eager scalar fields (no lazy state after the tx closes).
        return patientRepository.findById(id).map(patientMapper::toResponse);
    }

    /** Poll the cache until the winner populates it or the budget elapses; returns the wrapper or null. */
    private Cache.ValueWrapper awaitCache(Cache cache, UUID id) {
        long deadline = System.nanoTime() + WAIT_BUDGET.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(WAIT_POLL.toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return null;
            }
            Cache.ValueWrapper wrapper = getQuietly(cache, id);
            if (wrapper != null) {
                return wrapper;
            }
        }
        return null;
    }

    private Cache.ValueWrapper getQuietly(Cache cache, UUID id) {
        try {
            return cache.get(id);
        } catch (RuntimeException ex) {
            log.warn("Cache GET failed [{}::{}] — serving from source: {}", cache.getName(), id, ex.toString());
            return null; // fail open: treat a cache error as a miss
        }
    }

    private void putQuietly(Cache cache, UUID id, PatientResponseDTO value) {
        try {
            cache.put(id, value); // value may be null → NullValue stored (negative cache)
        } catch (RuntimeException ex) {
            log.warn("Cache PUT failed [{}::{}]: {}", cache.getName(), id, ex.toString());
        }
    }

    private Optional<PatientResponseDTO> toOptional(Cache.ValueWrapper wrapper) {
        return Optional.ofNullable((PatientResponseDTO) wrapper.get());
    }
}
