package com.pm.patientservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;

import com.pm.patientservice.model.OutboxEvent;
import com.pm.patientservice.repository.OutboxEventRepository;

/**
 * Proves the relay is <b>multi-instance safe</b>: with {@code SELECT … FOR UPDATE SKIP LOCKED}, a
 * second relay claiming a batch <b>skips</b> the rows a first relay already locked and grabs different
 * ones — so two replicas never publish the same event. Requires a real MariaDB (SKIP LOCKED is not an
 * H2 thing), and two genuinely concurrent transactions, so the test drives the transactions itself
 * ({@code NOT_SUPPORTED} disables the usual test-level transaction; schema via Hibernate, no relay
 * bean loaded — {@code @DataJpaTest} is JPA-only).
 *
 * <p>If SKIP LOCKED were <em>not</em> applied, the second query would block on the first's locks and
 * this test would deadlock (the first only releases after the second returns) → {@code @Timeout} fails it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
// Real Flyway schema (not ddl-auto) so idx_outbox_unpublished exists — the range-scan index is what
// makes FOR UPDATE lock only the returned rows; without it MariaDB filesorts and over-locks the batch.
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
@DisplayName("Outbox relay — SKIP LOCKED gives concurrent relays disjoint batches")
class OutboxSkipLockedIntegrationTest {

    static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.7");

    static {
        MARIADB.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MARIADB::getJdbcUrl);
        registry.add("spring.datasource.username", MARIADB::getUsername);
        registry.add("spring.datasource.password", MARIADB::getPassword);
    }

    @Autowired private OutboxEventRepository outboxRepository;
    @Autowired private PlatformTransactionManager txManager;

    private TransactionTemplate newTransaction() {
        TransactionTemplate template = new TransactionTemplate(txManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    @BeforeEach
    void seedFourUnpublishedRows() {
        newTransaction().executeWithoutResult(status -> {
            outboxRepository.deleteAllInBatch();
            for (int i = 0; i < 4; i++) {
                outboxRepository.save(OutboxEvent.forPatientRegistered(UUID.randomUUID(), "{}", null));
            }
        });
    }

    @Test
    @Timeout(30)
    @DisplayName("a second relay skips the rows the first has locked and claims different ones")
    void concurrentBatchesAreDisjoint() throws Exception {
        long seeded = newTransaction().execute(status -> outboxRepository.count());
        assertThat(seeded).as("seed should have committed 4 unpublished rows").isEqualTo(4L);

        // Sanity: single-threaded, the lock query must return a batch (isolates query bug vs concurrency).
        List<UUID> solo = newTransaction().execute(status ->
                outboxRepository.lockUnpublishedBatch(2).stream()
                        .map(OutboxEvent::getId).toList());
        assertThat(solo).as("single relay should lock 2 of the 4 rows").hasSize(2);

        CountDownLatch firstRelayHasLocked = new CountDownLatch(1);
        CountDownLatch releaseFirstRelay = new CountDownLatch(1);
        List<UUID> batchA = new CopyOnWriteArrayList<>();

        // Relay A: lock 2 rows in an open transaction and hold them until released.
        Thread relayA = new Thread(() -> newTransaction().executeWithoutResult(status -> {
            outboxRepository.lockUnpublishedBatch(2)
                    .forEach(event -> batchA.add(event.getId()));
            firstRelayHasLocked.countDown();
            awaitQuietly(releaseFirstRelay);
        }));
        relayA.start();
        assertThat(firstRelayHasLocked.await(10, TimeUnit.SECONDS)).isTrue();

        // Relay B (while A still holds its lock): must SKIP A's rows and get the other two.
        List<UUID> batchB = newTransaction().execute(status ->
                outboxRepository.lockUnpublishedBatch(2).stream()
                        .map(OutboxEvent::getId)
                        .toList());

        releaseFirstRelay.countDown();
        relayA.join(TimeUnit.SECONDS.toMillis(10));

        assertThat(batchA).hasSize(2);
        assertThat(batchB).hasSize(2);
        assertThat(batchA).doesNotContainAnyElementsOf(batchB); // disjoint — not blocked, not duplicated
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
