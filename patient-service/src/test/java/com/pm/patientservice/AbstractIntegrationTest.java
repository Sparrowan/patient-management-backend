package com.pm.patientservice;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base for integration tests. Boots the full application context against a real MariaDB in a
 * container (matching the production engine — native {@code UUID}, InnoDB, Flyway all exercised
 * for real, unlike an in-memory H2). {@code @ServiceConnection} wires the container's JDBC
 * details into Spring's datasource automatically.
 *
 * <p>The container is {@code static}, so it starts once and is shared across all integration
 * test classes in the JVM.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.7");
}
