package com.pm.billingservice;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

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
 * Base for integration tests — boots the full context against a real MariaDB (matching the
 * production engine; Flyway + native UUID exercised for real, unlike H2). Singleton-container
 * pattern: started once in a static block, never stopped by the framework.
 *
 * <p><b>Auth:</b> billing is now a resource server, so we mock the {@code JwtDecoder} (no
 * auth-service in tests) to accept any token as an <em>admin</em> and attach a bearer header to
 * every request — CRUD/money tests focus on their logic; the 401 rule is asserted separately.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

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
                .subject("test-admin")
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
        // Turn OFF the payout saga's periodic trigger so it can't race table resets between tests;
        // the payout worker test drives the worker directly for deterministic behavior.
        registry.add("payout.saga.enabled", () -> "false");
        // Likewise the idempotency TTL sweep — the retention worker test drives it directly.
        registry.add("idempotency.retention.enabled", () -> "false");
        // And the ledger archive sweep: a tick mid-test would move rows out from under a fixture.
        registry.add("ledger.archive.enabled", () -> "false");
        // Kafka is NOT part of these tests, and must not be reachable from them. Left alone, the
        // context resolves spring.kafka.bootstrap-servers to its localhost:9092 default — a real
        // broker, if one happens to be running — and then: the listener joins the PRODUCTION
        // consumer group (billing-service), stealing partitions from the running service and committing
        // offsets on its behalf; auto-offset-reset=earliest replays the whole topic into this test
        // database; and KafkaAdmin provisions topics on that broker. A suite should not be able to
        // perturb a running system. Pointing at a closed port fails fast (connection refused, not a
        // timeout) and auto-startup=false means no consumer group is joined at all. The event paths
        // are covered by driving the consumer/projector directly, which needs no broker.
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }
}
