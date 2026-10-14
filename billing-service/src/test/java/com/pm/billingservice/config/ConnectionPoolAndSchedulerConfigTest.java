package com.pm.billingservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.pm.billingservice.AbstractIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Pins billing's capacity settings against the live beans, not the properties file.
 *
 * <p>Asserting the bean rather than the file is the whole point: in patient-service the identical
 * {@code spring.datasource.hikari.*} block turned out to be <em>inert</em>, because that service
 * hand-builds its DataSource and those properties only bind to Boot's auto-configured one. Billing
 * does use the auto-configured DataSource, so here they apply — but "they apply" is a claim worth
 * checking rather than assuming, since nothing fails when they don't.
 */
@DisplayName("Connection pool and scheduler capacity")
class ConnectionPoolAndSchedulerConfigTest extends AbstractIntegrationTest {

    @Autowired private ThreadPoolTaskScheduler taskScheduler;
    @Autowired private DataSource dataSource;

    @Test
    @DisplayName("a thread per scheduled job, so an archive sweep cannot stall the payout saga")
    void schedulerIsNotSingleThreaded() {
        // Measured before this change: 1. Billing runs three scheduled jobs, so the ledger archive
        // sweep (up to 20 x 500 = 10,000 rows moved) held the only thread while the payout saga
        // worker — a 2s poll that settles real money — did not run at all.
        assertThat(taskScheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
                .as("one thread means the money-settling poller queues behind the heaviest sweep")
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("the pool is a chosen size and borrowing is bounded")
    void poolIsDeclaredAndBounded() {
        HikariDataSource hikari = (HikariDataSource) dataSource;

        assertThat(hikari.getMaximumPoolSize())
                .as("must be a chosen number, not Spring's inherited default of 10")
                .isNotEqualTo(10);
        assertThat(hikari.getConnectionTimeout())
                .as("unbounded borrowing turns pool exhaustion into a pile-up rather than an error")
                .isLessThanOrEqualTo(5_000L);
        // max_connections is 151; reserve ~10 for ops. This must leave room for a real fleet.
        assertThat(141 / hikari.getMaximumPoolSize())
                .as("pool of %d → ceiling of %d instances per database",
                        hikari.getMaximumPoolSize(), 141 / hikari.getMaximumPoolSize())
                .isGreaterThanOrEqualTo(10);
    }
}
