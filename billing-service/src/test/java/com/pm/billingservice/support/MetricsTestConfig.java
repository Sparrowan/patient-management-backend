package com.pm.billingservice.support;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Supplies a real {@link MeterRegistry} to web-slice tests. {@code @WebMvcTest} doesn't auto-configure
 * metrics, but it does load the idempotency {@code WebMvcConfigurer} + interceptor, which needs one —
 * a {@link SimpleMeterRegistry} (real, so counters work) rather than a mock.
 */
@TestConfiguration
public class MetricsTestConfig {

    @Bean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
