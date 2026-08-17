package com.pm.patientservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource-server security. Every {@code /api/**} request must carry a valid JWT — Spring verifies
 * the RS256 signature locally against auth-service's JWKS (fetched once, cached), plus expiry — so
 * there's no session and no per-request call to auth-service. Health + API docs stay public so
 * container probes and Swagger keep working. Stateless + CSRF off: there's no session cookie to
 * protect, only a bearer token. The {@code JwtDecoder} is auto-configured from the
 * {@code jwk-set-uri} property.
 *
 * <p>{@code @EnableMethodSecurity} turns on {@code @PreAuthorize}, so individual operations can
 * require a role (e.g. deleting a patient is admin-only).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    /**
     * By default Spring only maps the {@code scope}/{@code scp} claim to authorities. Our tokens
     * carry a custom {@code roles} claim (space-separated, already {@code ROLE_}-prefixed), so map
     * that instead — with an empty prefix, since the prefix is already in the claim.
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
