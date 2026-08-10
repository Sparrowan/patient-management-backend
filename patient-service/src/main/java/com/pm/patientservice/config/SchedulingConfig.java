package com.pm.patientservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables {@code @Scheduled}, which drives the {@link com.pm.patientservice.outbox.OutboxRelay}
 * poller that ships outbox rows to Kafka. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
