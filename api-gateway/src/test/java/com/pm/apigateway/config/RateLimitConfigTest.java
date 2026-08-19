package com.pm.apigateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.security.Principal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * The rate-limit key: authenticated user (JWT {@code sub}) if present, else client IP (first
 * {@code X-Forwarded-For} hop, else the socket address).
 */
@DisplayName("RateLimitConfig.clientKeyResolver")
class RateLimitConfigTest {

    private final KeyResolver resolver = new RateLimitConfig().clientKeyResolver();

    @Test
    @DisplayName("authenticated → keys on the principal name (the JWT sub)")
    void keysOnPrincipal() {
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        Principal principal = () -> "user-123";
        when(exchange.getPrincipal()).thenReturn(Mono.just(principal));

        assertThat(resolver.resolve(exchange).block()).isEqualTo("user-123");
    }

    @Test
    @DisplayName("unauthenticated → keys on the first X-Forwarded-For hop")
    void keysOnForwardedFor() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/").header("X-Forwarded-For", "9.9.9.9, 1.1.1.1"));

        assertThat(resolver.resolve(exchange).block()).isEqualTo("9.9.9.9");
    }

    @Test
    @DisplayName("unauthenticated, no XFF → keys on the socket address")
    void keysOnRemoteAddress() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/").remoteAddress(new InetSocketAddress("5.6.7.8", 40000)));

        assertThat(resolver.resolve(exchange).block()).isEqualTo("5.6.7.8");
    }
}
