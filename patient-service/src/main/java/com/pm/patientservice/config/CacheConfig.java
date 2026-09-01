package com.pm.patientservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pm.patientservice.dto.PatientResponseDTO;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter.TtlFunction;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis-backed cache-aside for the read-heavy {@code GET /api/v1/patients/{id}}.
 *
 * <p><b>Serialization:</b> the {@code patients} cache uses a Jackson serializer <em>bound to</em>
 * {@link PatientResponseDTO} — deliberately not the generic serializer. The generic one needs
 * Jackson default typing (an embedded {@code @class}), which is a deserialization-gadget risk if the
 * store is ever poisoned; a type-bound serializer sidesteps it and still serializes the record's
 * {@code LocalDate} correctly (the injected {@link ObjectMapper} carries the JavaTime module). The
 * trade-off is that this cache holds exactly one value type — which is true here.
 *
 * <p><b>Fail open:</b> caching is an optimization, not a source of truth, so a Redis outage must not
 * fail requests. The {@link CacheErrorHandler} logs and swallows cache errors, degrading to the DB.
 * (Note the eviction caveat: swallowing an <em>evict</em> error can leave a stale entry until its TTL
 * lapses — the bounded TTL is the backstop.)
 *
 * <p><b>Stampede protection:</b> per-entry TTL carries jitter (see {@link #jitteredTtl()}) so keys
 * don't expire in lockstep; single-flight ({@code sync=true}) lives on the cached reader. Absent
 * lookups are negatively cached too (same jittered base TTL — {@link #jitteredTtl()} explains why a
 * shorter negative TTL isn't used alongside {@code sync=true}).
 */
@Configuration
@EnableCaching
@RequiredArgsConstructor
public class CacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    /** Cache name for single-patient reads. */
    public static final String PATIENTS_CACHE = "patients";
    /** Base TTL for a cached entry (hit or negatively-cached miss). */
    private static final Duration BASE_TTL = Duration.ofMinutes(10);
    /** ±fraction of jitter applied to the base TTL so keys don't all expire on the same tick. */
    private static final double JITTER_RATIO = 0.2;

    private final ObjectMapper objectMapper;

    /**
     * The Redis cache manager, active by default. Guarded so tests can set
     * {@code spring.cache.type=none} to skip it and let Boot supply a no-op manager instead —
     * defining this bean unconditionally would otherwise override that property.
     */
    @Bean
    @ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = true)
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(jitteredTtl())
                .prefixCacheNameWith("patient-service::")
                .serializeKeysWith(SerializationPair.fromSerializer(new StringRedisSerializer()));

        RedisCacheConfiguration patients = defaults.serializeValuesWith(
                SerializationPair.fromSerializer(
                        new Jackson2JsonRedisSerializer<>(objectMapper, PatientResponseDTO.class)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .withCacheConfiguration(PATIENTS_CACHE, patients)
                .build();
    }

    /**
     * Per-entry TTL: {@link #BASE_TTL} ± {@link #JITTER_RATIO} of <b>jitter</b>, so a burst of keys
     * populated together don't all expire on the same second and manufacture a stampede on a timer.
     *
     * <p><b>Why not a per-value TTL</b> (e.g. a shorter TTL for negatively-cached misses)? Because the
     * reader uses {@code sync = true}, and in the synchronized path Spring Data Redis computes the TTL
     * <em>before</em> loading the value — so a value-aware {@code TtlFunction} would see {@code null}
     * for every entry and mis-classify hits as misses. Single-flight (sync) is worth more here than a
     * shorter negative TTL, so we keep sync and give every entry the same jittered base TTL. That's
     * safe: keys are unguessable UUID PKs that are never reused, so a lingering cached miss is harmless.
     */
    private TtlFunction jitteredTtl() {
        return (key, value) -> {
            double factor = 1.0 + (ThreadLocalRandom.current().nextDouble() * 2.0 - 1.0) * JITTER_RATIO;
            return Duration.ofMillis((long) (BASE_TTL.toMillis() * factor));
        };
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException ex, Cache cache, Object key) {
                log.warn("Cache GET failed [{}::{}] — serving from source: {}", cache.getName(), key, ex.toString());
            }

            @Override
            public void handleCachePutError(RuntimeException ex, Cache cache, Object key, Object value) {
                log.warn("Cache PUT failed [{}::{}]: {}", cache.getName(), key, ex.toString());
            }

            @Override
            public void handleCacheEvictError(RuntimeException ex, Cache cache, Object key) {
                log.warn("Cache EVICT failed [{}::{}] — entry may be stale until TTL: {}",
                        cache.getName(), key, ex.toString());
            }

            @Override
            public void handleCacheClearError(RuntimeException ex, Cache cache) {
                log.warn("Cache CLEAR failed [{}]: {}", cache.getName(), ex.toString());
            }
        };
    }
}
