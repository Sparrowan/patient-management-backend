package com.pm.patientservice.service;

import com.pm.patientservice.config.CacheConfig;
import com.pm.patientservice.dto.PatientResponseDTO;
import com.pm.patientservice.mapper.PatientMapper;
import com.pm.patientservice.repository.PatientRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cache-aside read boundary for a single patient. Kept as a separate bean (not a method on the
 * service) for two reasons:
 *
 * <ul>
 *   <li><b>Negative caching without breaking convention.</b> Our services throw for not-found and
 *       never return null. But to cache an <em>absence</em> the cached method must return a value, not
 *       throw (exceptions aren't cached). So this data-layer method returns {@link Optional} — an
 *       empty result is cached, stopping repeated lookups for a missing id from hitting the DB —
 *       while the service keeps throwing {@code PatientNotFoundException}.</li>
 *   <li><b>The proxy actually fires.</b> {@code @Cacheable} works through the Spring proxy; a service
 *       method calling a cached method on {@code this} would self-invoke and bypass the cache. A
 *       separate bean means the call crosses the proxy boundary.</li>
 * </ul>
 *
 * <p><b>{@code sync = true}</b> is single-flight: on a cold key only one thread runs the load while
 * the rest wait for the result — so a burst of concurrent requests for the same id can't stampede the
 * DB. Caveat: this lock is <em>per JVM</em>; across replicas N threads (one per instance) can still
 * miss together. A cross-instance guarantee needs a distributed lock (Redis {@code SET NX PX}) — the
 * next level up, deliberately not added here (per-JVM single-flight + TTL jitter covers the common case).
 *
 * <p>{@code @Transactional(readOnly = true)} sits here, not on the service read, so a cache hit
 * short-circuits before any transaction is opened.
 */
@Component
@RequiredArgsConstructor
public class PatientCacheReader {

    private final PatientRepository patientRepository;
    private final PatientMapper patientMapper;

    @Cacheable(cacheNames = CacheConfig.PATIENTS_CACHE, key = "#id", sync = true)
    @Transactional(readOnly = true)
    public Optional<PatientResponseDTO> find(UUID id) {
        // findById excludes soft-deleted rows (@SQLRestriction), so a deleted patient reads as absent
        // and is negatively cached — correct: it's "not found".
        return patientRepository.findById(id).map(patientMapper::toResponse);
    }
}
