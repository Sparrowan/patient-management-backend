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
        // Turn off the outbox retention tick so it can't prune rows a test just set up; the retention
        // test drives the worker directly.
        registry.add("outbox.retention.enabled", () -> "false");
        // Kafka is NOT part of these tests, and must not be reachable from them. Left alone, the
        // context resolves spring.kafka.bootstrap-servers to its localhost:9092 default — a real
        // broker, if one happens to be running — and then: the listener joins the PRODUCTION
        // consumer group (n/a — producer only), stealing partitions from the running service and committing
        // offsets on its behalf; auto-offset-reset=earliest replays the whole topic into this test
        // database; and KafkaAdmin provisions topics on that broker. A suite should not be able to
        // perturb a running system. Pointing at a closed port fails fast (connection refused, not a
        // timeout) and auto-startup=false means no consumer group is joined at all. The event paths
        // are covered by driving the consumer/projector directly, which needs no broker.
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        // And the outbox relay's tick. Not cosmetic: its claim query gap-locks the unpublished
        // range for the duration of its Kafka send, so against an unreachable broker it blocks the
        // very INSERT a registration performs, and POST /patients fails with a lock-wait timeout.
        // See the known defect on OutboxRelay. The relay's own behaviour is covered by its unit test
        // and by OutboxSkipLockedIntegrationTest, which drive it directly.
        registry.add("outbox.relay.enabled", () -> "false");
    }
}
