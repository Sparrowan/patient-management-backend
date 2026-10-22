package com.pm.authservice;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;

/**
 * Base for auth-service integration tests — boots the full context against a real MariaDB, matching
 * the production engine so Flyway, the native {@code UUID} column type and (most importantly here)
 * the <b>unique constraints on username/email</b> are exercised for real. Those constraints are the
 * actual guard against duplicate registration — the {@code existsBy} checks in {@code UserService}
 * only turn the common case into a friendly 409 — so testing them against H2 would be testing a
 * different database's idea of uniqueness.
 *
 * <p>Singleton-container pattern: started once in a static block, never stopped by the framework,
 * so every test class in the suite shares one container and one Spring context. Same shape as
 * patient/billing/analytics.
 *
 * <p><b>No mocked beans here, deliberately.</b> Adding a {@code @MockitoBean} to this base would be
 * convenient, but every subclass that adds its own override forks the context cache key and pays a
 * fresh application start. Auth has nothing external to stub anyway — no Kafka, no gRPC, no
 * downstream service — so the whole service is testable for real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.7");

    static {
        MARIADB.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MARIADB::getJdbcUrl);
        registry.add("spring.datasource.username", MARIADB::getUsername);
        registry.add("spring.datasource.password", MARIADB::getPassword);
        // Tracing would otherwise try to reach a Jaeger collector that is not running in tests and
        // log an export failure every few seconds, drowning the real output.
        registry.add("management.tracing.sampling.probability", () -> "0.0");
    }
}
