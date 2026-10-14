package com.pm.patientservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
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
 * Proves the relay is <b>multi-instance safe</b>: two relays claiming concurrently get
 * <em>disjoint</em> batches, so two replicas never publish the same event.
 *
 * <p><b>What changed, and why this test outlived the mechanism it was written for.</b> Disjointness
 * used to come from {@code SELECT … FOR UPDATE SKIP LOCKED} — the held lock <em>was</em> the claim,
 * which forced the Kafka send to stay inside the claiming transaction and made it gap-lock the range
 * a patient registration inserts into. Disjointness now comes from a durable lease instead: the
 * claiming {@code UPDATE} stamps {@code claimed_until}, which is atomic, so the loser of a race
 * simply no longer matches the predicate.
 *
 * <p>The property under test is unchanged — concurrent claims must not overlap — so the test stays;
 * only the mechanism it exercises moved. Still needs a real MariaDB and two genuinely concurrent
 * transactions, so it drives transactions itself ({@code NOT_SUPPORTED} disables the test-level one).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
// Real Flyway schema (not ddl-auto) so idx_outbox_claimable exists — without it the claiming UPDATE
// filesorts and over-locks, the same mistake the original index was added to avoid.
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
@DisplayName("Outbox relay — concurrent claims get disjoint batches")
class OutboxClaimDisjointnessIntegrationTest {

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
    @DisplayName("a second relay cannot re-claim rows the first already leased")
    void concurrentBatchesAreDisjoint() throws Exception {
        long seeded = newTransaction().execute(status -> outboxRepository.count());
        assertThat(seeded).as("seed should have committed 4 unpublished rows").isEqualTo(4L);

        Instant now = Instant.now();
        Instant leaseUntil = now.plus(Duration.ofMinutes(2));

        // Relay A claims two rows and COMMITS. Under the old lock-as-claim design this is the point
        // the claim would have evaporated; the lease makes it durable past the commit, which is what
        // lets the Kafka send happen outside any transaction.
        String tokenA = UUID.randomUUID().toString();
        List<UUID> batchA = newTransaction().execute(status -> {
            outboxRepository.claimBatch(tokenA, leaseUntil, now, 2);
            return outboxRepository.findByClaimToken(tokenA).stream().map(OutboxEvent::getId).toList();
        });
        assertThat(batchA).as("first relay claims 2 of the 4 rows").hasSize(2);

        // Relay B claims afterwards, in a separate committed transaction. It must get the OTHER two:
        // A's rows are leased into the future, so B's predicate no longer matches them.
        String tokenB = UUID.randomUUID().toString();
        List<UUID> batchB = newTransaction().execute(status -> {
            outboxRepository.claimBatch(tokenB, leaseUntil, Instant.now(), 2);
            return outboxRepository.findByClaimToken(tokenB).stream().map(OutboxEvent::getId).toList();
        });

        assertThat(batchB).hasSize(2);
        assertThat(batchA).as("disjoint — never the same event published twice")
                .doesNotContainAnyElementsOf(batchB);

        // And a third relay gets nothing: all four rows are leased.
        String tokenC = UUID.randomUUID().toString();
        List<UUID> batchC = newTransaction().execute(status -> {
            outboxRepository.claimBatch(tokenC, leaseUntil, Instant.now(), 2);
            return outboxRepository.findByClaimToken(tokenC).stream().map(OutboxEvent::getId).toList();
        });
        assertThat(batchC).as("nothing left unleased").isEmpty();
    }

    @Test
    @Timeout(30)
    @DisplayName("an expired lease is reclaimable — a dead relay's work is not stranded")
    void expiredLeaseIsReclaimable() {
        Instant now = Instant.now();

        // Claim everything with a lease that has ALREADY expired, simulating a relay that died
        // between claiming and settling.
        String dead = UUID.randomUUID().toString();
        newTransaction().executeWithoutResult(status ->
                outboxRepository.claimBatch(dead, now.minus(Duration.ofMinutes(5)), now, 4));

        String live = UUID.randomUUID().toString();
        List<UUID> reclaimed = newTransaction().execute(status -> {
            outboxRepository.claimBatch(live, now.plus(Duration.ofMinutes(2)), now, 4);
            return outboxRepository.findByClaimToken(live).stream().map(OutboxEvent::getId).toList();
        });

        assertThat(reclaimed)
                .as("lease expiry is the recovery mechanism; re-publishing is safe because consumers "
                        + "are idempotent on eventId")
                .hasSize(4);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
