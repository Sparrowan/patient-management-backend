package com.pm.patientservice;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.pm.patientservice.grpc.BillingGrpcClient;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MariaDBContainer;

/**
 * Base for integration tests. Boots the full application context against a real MariaDB in a
 * container (matching the production engine — native {@code UUID}, InnoDB, Flyway all exercised
 * for real, unlike an in-memory H2).
 *
 * <p><b>Singleton container pattern:</b> the container is started once in a static initializer
 * and never stopped by the test framework (Testcontainers' Ryuk reaps it at JVM exit).
 *
 * <p><b>Auth:</b> the app is a resource server now, so requests need a token. We mock the
 * {@code JwtDecoder} (no auth-service in tests) to accept any token and attach a bearer header to
 * every request — the CRUD tests focus on CRUD, and the auth <em>rules</em> (401 without a token)
 * are asserted separately.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    // The delete flow makes a synchronous gRPC veto to billing; there's no billing server in these
    // tests, so mock it (a no-op veto = "allowed"). The gRPC path itself is covered by unit tests.
    @MockitoBean
    protected BillingGrpcClient billingGrpcClient;

    // Replaces the real JwtDecoder (which would try to fetch auth-service's JWKS).
    @MockitoBean
    protected JwtDecoder jwtDecoder;

    @Autowired
    private TestRestTemplate restTemplate;

    static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.7");

    static {
        MARIADB.start();
    }

    @BeforeEach
    void authenticateEveryRequest() {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("test-user")
                .claim("roles", "ROLE_ADMIN")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
        when(jwtDecoder.decode(anyString())).thenReturn(jwt);

        var interceptors = restTemplate.getRestTemplate().getInterceptors();
        interceptors.clear();
        interceptors.add((request, body, execution) -> {
            request.getHeaders().setBearerAuth("test-token");
            return execution.execute(request, body);
        });
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MARIADB::getJdbcUrl);
        registry.add("spring.datasource.username", MARIADB::getUsername);
        registry.add("spring.datasource.password", MARIADB::getPassword);
        // Disable caching in tests: the DB is truncated directly between cases (not via deletePatient),
        // which wouldn't evict a cache, so a live Redis would serve stale entries. Cache-aside behavior
        // is verified live in Docker instead. NONE swaps in a no-op CacheManager.
        registry.add("spring.cache.type", () -> "none");
    }
}
