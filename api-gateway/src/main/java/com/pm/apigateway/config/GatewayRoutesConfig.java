package com.pm.apigateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Routing table for the single entry point. Each route matches a path prefix and forwards to the
 * owning service (over the compose network in Docker, localhost in dev). Using the Java
 * {@link RouteLocator} DSL rather than YAML keeps this stable across Gateway versions (the config
 * prefix has moved between releases) and makes the routes easy to read.
 *
 * <p>Bit 1 is routing only — no auth here yet. Edge JWT validation is the next bit; services already
 * validate independently (zero-trust), so nothing is exposed in the meantime.
 */
@Configuration
public class GatewayRoutesConfig {

    private final String authUri;
    private final String patientUri;
    private final String billingUri;

    public GatewayRoutesConfig(
            @Value("${gateway.uris.auth:http://localhost:4002}") String authUri,
            @Value("${gateway.uris.patient:http://localhost:4000}") String patientUri,
            @Value("${gateway.uris.billing:http://localhost:4001}") String billingUri) {
        this.authUri = authUri;
        this.patientUri = patientUri;
        this.billingUri = billingUri;
    }

    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder) {
        return builder.routes()
                // auth-service: login/register + the JWKS the resource servers fetch.
                .route("auth", r -> r.path("/api/v1/auth/**", "/oauth2/jwks").uri(authUri))
                .route("patients", r -> r.path("/api/v1/patients/**").uri(patientUri))
                .route("billing", r -> r.path("/api/v1/billing-accounts/**").uri(billingUri))
                .build();
    }
}
