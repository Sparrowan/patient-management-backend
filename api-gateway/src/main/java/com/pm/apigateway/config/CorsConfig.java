package com.pm.apigateway.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * CORS, owned by the gateway (the single entry point) so the browser contract is defined in one place
 * — services behind it never see cross-origin preflights. Wired into the security chain (see
 * {@link SecurityConfig}) so Spring Security answers the preflight {@code OPTIONS} before authorization,
 * rather than the token check rejecting it.
 *
 * <p><b>Reactive</b> {@code CorsConfigurationSource} (the {@code org.springframework.web.cors.reactive}
 * one, not the servlet type — WebFlux). Allowed origins are externalized: a real front-end origin per
 * environment, never {@code *} together with credentials (the spec forbids it, and we allow credentials
 * so the browser may send the bearer token).
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${gateway.cors.allowed-origins:http://localhost:3000}") List<String> allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L); // cache the preflight for an hour

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
