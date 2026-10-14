package com.pm.patientservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.pm.patientservice.AbstractIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Pins the two capacity settings that were previously inherited rather than chosen.
 *
 * <p>Neither has a functional symptom when wrong, which is why both drifted unnoticed. A
 * single-threaded scheduler still runs every job — just one at a time, so a long sweep silently
 * delays a poller. An undeclared pool still serves every request — it just reserves more
 * connections per instance than anyone counted, lowering the fleet ceiling invisibly. Only an
 * explicit assertion catches either.
 */
@DisplayName("Connection pool and scheduler capacity")
class ConnectionPoolAndSchedulerConfigTest extends AbstractIntegrationTest {

    @Autowired private ThreadPoolTaskScheduler taskScheduler;

    @Autowired
    @Qualifier("primaryDataSource")
    private DataSource primaryDataSource;

    @Autowired
    @Qualifier("replicaDataSource")
    private DataSource replicaDataSource;

    @Test
    @DisplayName("the scheduler has a thread per scheduled job, so sweeps cannot delay pollers")
    void schedulerIsNotSingleThreaded() {
        // Measured before this change: 1. Every @Scheduled job in the service ran on that one
        // thread, so a retention sweep blocked the outbox relay's 1s poll for its whole duration.
        assertThat(taskScheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
                .as("a single-threaded scheduler serialises every @Scheduled job in the service")
                .isGreaterThan(1);
    }

    @Test
    @DisplayName("BOTH pools are sized explicitly — the replica's is not covered by spring.datasource.hikari.*")
    void bothPoolsAreDeclared() {
        HikariDataSource primary = (HikariDataSource) primaryDataSource;
        HikariDataSource replica = (HikariDataSource) replicaDataSource;

        // The trap this guards: spring.datasource.hikari.* binds only to the auto-configured
        // primary. The replica is hand-built, so it silently took Hikari's own default of 10 — ten
        // connections per instance that the fleet-wide ceiling never accounted for.
        assertThat(primary.getMaximumPoolSize())
                .as("primary pool must be a chosen number, not Spring's inherited default of 10")
                .isNotEqualTo(10);
        assertThat(replica.getMaximumPoolSize())
                .as("replica pool must be a chosen number, not Hikari's inherited default of 10")
                .isNotEqualTo(10);

        // Reads on the replica are the cheap ones and sit behind Redis; the primary carries every
        // write plus the freshness-pinned reads.
        assertThat(replica.getMaximumPoolSize())
                .as("the replica should not reserve more capacity than the primary")
                .isLessThanOrEqualTo(primary.getMaximumPoolSize());
    }

    @Test
    @DisplayName("an exhausted pool fails fast instead of hanging the request")
    void borrowingIsBounded() {
        assertThat(((HikariDataSource) primaryDataSource).getConnectionTimeout())
                .as("unbounded borrowing turns pool exhaustion into a pile-up rather than an error")
                .isLessThanOrEqualTo(5_000L);
        assertThat(((HikariDataSource) replicaDataSource).getConnectionTimeout())
                .isLessThanOrEqualTo(5_000L);
    }

    @Test
    @DisplayName("the per-instance connection budget is small enough to leave room for replicas")
    void perInstanceBudgetLeavesHeadroom() {
        int perInstance = ((HikariDataSource) primaryDataSource).getMaximumPoolSize()
                + ((HikariDataSource) replicaDataSource).getMaximumPoolSize();

        // MariaDB's default max_connections is 151. Reserve ~10 for ops and replication, and this
        // service must still fit several instances: replicas x perInstance < 141.
        assertThat(perInstance)
                .as("two pools per instance: %d connections means a ceiling of %d instances per DB",
                        perInstance, 141 / perInstance)
                .isLessThanOrEqualTo(20);
        assertThat(141 / perInstance)
                .as("fleet should be able to reach at least 8 instances before exhausting connections")
                .isGreaterThanOrEqualTo(8);
    }
}
