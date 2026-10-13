package com.pm.patientservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.pm.patientservice.model.OutboxEvent;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;

/**
 * The regression test for the defect that motivated the claim lease.
 *
 * <p>The old relay claimed its batch with {@code SELECT … FOR UPDATE SKIP LOCKED} and held it across
 * the Kafka send. {@code SKIP LOCKED} spares other <em>readers</em> of the same lock, but under
 * REPEATABLE READ the {@code published_at IS NULL} range scan also gap-locks the range — and a
 * patient registration inserts into precisely that range. A slow broker therefore blocked
 * registrations. This asserts the property that fixes it: <b>a held claim does not delay an insert
 * into the claimed range.</b>
 *
 * <p>Deliberately its own context with a dedicated container: the point is to observe real InnoDB
 * locking across two concurrent connections, which an in-memory database cannot reproduce.
 */
@SpringBootTest
@DisplayName("Outbox claim vs. concurrent writer")
class OutboxClaimDoesNotBlockWritersTest {

    /** Generous enough to be unambiguous: a gap-locked insert would stall for seconds, not millis. */
    private static final Duration BLOCKED_THRESHOLD = Duration.ofSeconds(3);

    /**
     * Fewer seeded rows than the claim asks for, on purpose — this is the condition that makes the
     * test bite, and it took a mutation run to establish.
     *
     * <p>Gap locks are taken over what the scan actually reads. Ask for exactly as many rows as
     * exist and the scan stops <em>at</em> the last record, never locking the trailing (supremum)
     * gap, so an appended row does not conflict and the old buggy code passes this test. Ask for
     * more than exist and the scan <b>exhausts the range</b>, locks the supremum gap, and blocks
     * appends. That is the real production shape: {@code BATCH_SIZE} is 100 against a handful of
     * pending rows. Verified by reinstating the old {@code FOR UPDATE} claim, which made the insert
     * below stall 8s instead of milliseconds.
     */
    private static final int SEEDED_ROWS = 5;
    private static final int CLAIM_LIMIT = 100;

    static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.7");

    static {
        MARIADB.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MARIADB::getJdbcUrl);
        registry.add("spring.datasource.username", MARIADB::getUsername);
        registry.add("spring.datasource.password", MARIADB::getPassword);
        registry.add("spring.cache.type", () -> "none");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("outbox.relay.enabled", () -> "false");
        registry.add("outbox.retention.enabled", () -> "false");
        // A short lock-wait timeout turns "blocked" into a fast, unambiguous failure rather than a
        // 50-second hang: if the claim still gap-locked, the insert below would error out here.
        registry.add("spring.datasource.hikari.connection-init-sql",
                () -> "SET SESSION innodb_lock_wait_timeout = 3");
    }

    @Autowired private OutboxClaimer claimer;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedUnpublishedRows() {
        jdbcTemplate.execute("DELETE FROM outbox_events");
        for (int i = 0; i < SEEDED_ROWS; i++) {
            insertUnpublished();
        }
    }

    private void insertUnpublished() {
        jdbcTemplate.update(
                "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, topic, "
                        + "payload, created_at, attempts) "
                        + "VALUES (?, 'Patient', ?, 'PatientRegistered', 'patient-events', '{}', NOW(6), 0)",
                UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    @DisplayName("an insert into the claimed range is NOT blocked while a claim is held")
    void claimDoesNotBlockInserts() throws Exception {
        List<OutboxEvent> claimed = claimer.claimBatch(CLAIM_LIMIT);
        assertThat(claimed).as("precondition: rows were actually claimed").hasSize(SEEDED_ROWS);

        // The claim has committed and its lease is still live — exactly the window during which the
        // old implementation held gap locks while talking to Kafka.
        long startedAt = System.nanoTime();
        CompletableFuture<Void> writer = CompletableFuture.runAsync(this::insertUnpublished);
        writer.get(); // would throw on a lock-wait timeout
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed)
                .as("a registration's outbox insert must not wait on the relay's claim")
                .isLessThan(BLOCKED_THRESHOLD);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class))
                .isEqualTo(6);
    }

    @Test
    @DisplayName("a second claim wins nothing while the first lease is live")
    void concurrentClaimsAreDisjoint() {
        assertThat(claimer.claimBatch(CLAIM_LIMIT)).hasSize(SEEDED_ROWS);
        assertThat(claimer.claimBatch(CLAIM_LIMIT))
                .as("every row is leased, so a competing relay must come away empty")
                .isEmpty();
    }

    @Test
    @DisplayName("settling a failure releases the claim, so the next tick retries immediately")
    void failureReleasesTheClaimForImmediateRetry() {
        List<OutboxEvent> claimed = claimer.claimBatch(1);
        assertThat(claimer.settleFailed(claimed.get(0).getId())).isEqualTo(1);

        assertThat(claimer.claimBatch(1))
                .as("a released row is claimable again without waiting out the lease")
                .extracting(OutboxEvent::getId)
                .containsExactly(claimed.get(0).getId());
    }

    @Test
    @DisplayName("a settled-published row never reappears in a claim")
    void publishedRowsAreNotReclaimed() {
        List<OutboxEvent> claimed = claimer.claimBatch(CLAIM_LIMIT);
        claimed.forEach(e -> claimer.settlePublished(e.getId()));

        assertThat(claimer.claimBatch(CLAIM_LIMIT)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL", Integer.class))
                .isZero();
    }
}
