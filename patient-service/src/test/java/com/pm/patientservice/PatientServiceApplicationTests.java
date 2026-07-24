package com.pm.patientservice;

import org.junit.jupiter.api.Test;

/**
 * Smoke test: the full application context boots (against the Testcontainers MariaDB from
 * {@link AbstractIntegrationTest}), which also proves the Flyway migrations apply and the JPA
 * mappings validate against the real schema.
 */
class PatientServiceApplicationTests extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
    }
}
