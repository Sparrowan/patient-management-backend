package com.pm.apigateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.reactive.CorsConfigurationSource;

/**
 * Edge JWT validation. The gateway now rejects a request with no/invalid token at the door (:4004)
 * instead of proxying it to a service only to be rejected there — a choke point + optimization, not
 * a replacement: the services stay resource servers, so a caller that bypasses the gateway (or a
 * compromised gateway) still can't skip auth (defense in depth / zero-trust).
 *
 * <p><b>Reactive, not servlet.</b> Spring Cloud Gateway is WebFlux, so this is the reactive Security
 * API — the shapes differ from patient/billing's servlet config:
 * <ul>
 *   <li>{@link SecurityWebFilterChain} + {@link ServerHttpSecurity} (not {@code SecurityFilterChain} /
 *       {@code HttpSecurity})</li>
 *   <li>{@code authorizeExchange} / {@code pathMatchers} (not {@code authorizeHttpRequests} /
 *       {@code requestMatchers})</li>
 *   <li>No {@code STATELESS} session policy to set — WebFlux security is sessionless by default.</li>
 * </ul>
 * The {@code ReactiveJwtDecoder} is auto-configured from the {@code jwk-set-uri} property (auth's
 * JWKS), so validation is local (RS256 signature + expiry), stateless, no per-request call to auth.
 *
 * <p>Only <b>authentication</b> happens here ("is this a valid token?"). Fine-grained <b>authorization</b>
 * (which role may do what) stays at the services via {@code @PreAuthorize} — the gateway forwards the
 * {@code Authorization} header untouched, so downstream authz is unaffected.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http, CorsConfigurationSource corsConfigurationSource) {
        return http
                // CORS handled here so Security answers the preflight OPTIONS before the token check.
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                // No session cookie to protect — only a bearer token — so CSRF doesn't apply.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange
                        // Public: getting a token (no token yet), the JWKS, and probes.
                        .pathMatchers("/api/v1/auth/**", "/oauth2/jwks", "/actuator/**").permitAll()
                        // Everything routed onward (patients, billing) needs a valid token.
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }
}
