package com.pm.apigateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Edge-auth behavior. The gateway boots without the downstream services or auth-service running:
 * the JWKS is fetched lazily on the first token decode, and an unauthenticated request is rejected
 * by the security filter <em>before</em> the routing filter runs — so no downstream is ever
 * contacted, which is exactly the property we want to assert.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("API Gateway — edge JWT validation")
class GatewayEdgeAuthTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("a protected route with no token is rejected at the edge (401), never routed onward")
    void protectedRouteWithoutTokenIsRejected() {
        webTestClient.get().uri("/api/v1/patients")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("login stays public — no token required to obtain one (not a 401)")
    void authRouteIsPublic() {
        // Reaches routing (auth-service isn't up in this test, so it can't be a 200) — the point is
        // security does NOT short-circuit it with a 401 the way it does a protected route.
        webTestClient.get().uri("/api/v1/auth/login")
                .exchange()
                .expectStatus().value(status -> org.assertj.core.api.Assertions.assertThat(status)
                        .isNotEqualTo(401));
    }

    @Test
    @DisplayName("actuator health stays public for probes (200)")
    void healthIsPublic() {
        webTestClient.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("CORS preflight from the allowed origin is answered before auth (no 401, echoes the origin)")
    void corsPreflightAllowed() {
        webTestClient.options().uri("/api/v1/patients")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:3000");
    }
}
