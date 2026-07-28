package com.pm.billingservice;

import org.junit.jupiter.api.Test;

/**
 * Smoke test: the full application context boots against the Testcontainers MariaDB from {@link
 * AbstractIntegrationTest}, proving the Flyway migrations apply and the JPA mappings validate.
 */
class BillingServiceApplicationTests extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
    }
}
