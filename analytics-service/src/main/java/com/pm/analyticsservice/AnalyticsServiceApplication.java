package com.pm.analyticsservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * analytics-service — the <b>CQRS read side</b> of the system.
 *
 * <p>It exposes no command API. Its state is built entirely by <em>projecting</em> domain events
 * (consumed from Kafka) into denormalized read models that the query endpoints serve. Keeping it a
 * separate, independently deployable service with its own datastore means read-heavy analytics load
 * and its reporting schema never touch the write-side services (patient/billing) — they stay a lean
 * command side, this scales and evolves on its own. The trade-off is <b>eventual consistency</b>:
 * the read model lags the source by the time it takes an event to flow through.
 */
@SpringBootApplication
public class AnalyticsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalyticsServiceApplication.class, args);
    }
}
