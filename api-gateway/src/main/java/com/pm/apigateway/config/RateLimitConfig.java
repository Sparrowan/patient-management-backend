package com.pm.apigateway.config;

import java.net.InetSocketAddress;
import java.security.Principal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Edge rate limiting. Gateway's {@code RequestRateLimiter} filter uses a <b>token bucket</b> stored in
 * Redis: each key refills at {@code replenishRate} tokens/sec and holds up to {@code burstCapacity}
 * tokens; a request costs one token, and an empty bucket → <b>429 Too Many Requests</b>. Redis (not
 * in-memory) so the limit is shared across gateway replicas and enforced atomically via a Lua script.
 */
@Configuration
public class RateLimitConfig {

    @Bean
    public RedisRateLimiter redisRateLimiter(
            @Value("${gateway.ratelimit.replenish-rate:10}") int replenishRate,
            @Value("${gateway.ratelimit.burst-capacity:20}") int burstCapacity) {
        return new RedisRateLimiter(replenishRate, burstCapacity);
    }

    /**
     * The bucket key: the authenticated user (JWT {@code sub}) when present, else the client IP. So a
     * logged-in caller is limited per-identity across devices, and unauthenticated traffic
     * (login/register) is limited per-IP — brute-force protection where there is no principal yet.
     */
    @Bean
    public KeyResolver clientKeyResolver() {
        // fromSupplier (not Mono.just(clientIp(...))) so the IP lookup is deferred — it runs only when
        // the principal is absent, not eagerly on every authenticated request.
        return exchange -> exchange.getPrincipal()
                .map(Principal::getName)
                .switchIfEmpty(Mono.fromSupplier(() -> clientIp(exchange)));
    }

    /** First {@code X-Forwarded-For} hop (we sit behind a proxy/LB in prod), else the socket address. */
    private static String clientIp(ServerWebExchange exchange) {
        String forwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote != null && remote.getAddress() != null
                ? remote.getAddress().getHostAddress()
                : "unknown";
    }
}
