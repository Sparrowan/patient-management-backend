package com.pm.patientservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The routing decision in isolation — no Spring context, no database. Drives
 * {@link TransactionSynchronizationManager} directly, which is exactly the signal the router reads.
 */
@DisplayName("RoutingDataSource")
class RoutingDataSourceTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final RoutingDataSource routing = new RoutingDataSource(meterRegistry);

    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    private Object route() {
        return routing.determineCurrentLookupKey();
    }

    @Test
    @DisplayName("a read-only transaction goes to the replica")
    void readOnlyGoesToReplica() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        assertThat(route()).isEqualTo(RoutingDataSource.REPLICA);
    }

    @Test
    @DisplayName("a write transaction goes to the primary")
    void writeGoesToPrimary() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

        assertThat(route()).isEqualTo(RoutingDataSource.PRIMARY);
    }

    @Test
    @DisplayName("no transaction context at all defaults to the primary (correct over fast)")
    void noTransactionDefaultsToPrimary() {
        assertThat(route()).isEqualTo(RoutingDataSource.PRIMARY);
    }

    @Test
    @DisplayName("ReadFreshness.fromPrimary overrides a read-only transaction")
    void forcedFreshReadOverridesReadOnly() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        Object target = ReadFreshness.fromPrimary(this::route);

        assertThat(target).isEqualTo(RoutingDataSource.PRIMARY);
    }

    @Test
    @DisplayName("the force-primary flag never leaks past the read that set it")
    void forcedFlagIsCleared() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        ReadFreshness.fromPrimary(this::route);

        // Back to normal routing on the same thread — no leak into a pooled thread's next request.
        assertThat(route()).isEqualTo(RoutingDataSource.REPLICA);
    }

    @Test
    @DisplayName("every routing decision is counted, tagged by target")
    void decisionsAreMetered() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        route();
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        route();

        assertThat(meterRegistry.counter("patient.datasource.routing", "target", "replica").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("patient.datasource.routing", "target", "primary").count())
                .isEqualTo(1.0);
    }
}
