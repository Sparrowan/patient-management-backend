package com.pm.patientservice;

import com.pm.patientservice.grpc.BillingGrpcClient;
import org.springframework.boot.test.context.SpringBootTest;
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
 * and never stopped by the test framework (Testcontainers' Ryuk reaps it at JVM exit). This is
 * deliberate — letting {@code @Testcontainers} manage a shared static container's lifecycle
 * stops it after the first test class, breaking every later class that reuses the cached Spring
 * context. Connection details are published via {@code @DynamicPropertySource}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    // The delete flow makes a synchronous gRPC veto to billing; there's no billing server in these
    // tests, so mock it (a no-op veto = "allowed"). The gRPC path itself is covered by unit tests.
    @MockitoBean
    protected BillingGrpcClient billingGrpcClient;

    static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.7");

    static {
        MARIADB.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MARIADB::getJdbcUrl);
        registry.add("spring.datasource.username", MARIADB::getUsername);
        registry.add("spring.datasource.password", MARIADB::getPassword);
    }
}
