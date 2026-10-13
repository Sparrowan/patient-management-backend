package com.pm.analyticsservice;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;

/**
 * Base for integration tests — boots the full context against a real MariaDB (matching production;
 * Flyway + Hibernate {@code validate} exercised for real, unlike H2). Singleton-container pattern:
 * started once in a static block, reused across the suite, never stopped by the framework.
 */
@SpringBootTest
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
        // Kafka is NOT part of these tests, and must not be reachable from them. Left alone, the
        // context resolves spring.kafka.bootstrap-servers to its localhost:9092 default — a real
        // broker, if one happens to be running — and then: the listener joins the PRODUCTION
        // consumer group (analytics-service), stealing partitions from the running service and committing
        // offsets on its behalf; auto-offset-reset=earliest replays the whole topic into this test
        // database; and KafkaAdmin provisions topics on that broker. A suite should not be able to
        // perturb a running system. Pointing at a closed port fails fast (connection refused, not a
        // timeout) and auto-startup=false means no consumer group is joined at all. The event paths
        // are covered by driving the consumer/projector directly, which needs no broker.
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }
}
