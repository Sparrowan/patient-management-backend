package com.pm.billingservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.pm.billingservice.dto.BillingAccountResponseDTO;
import com.pm.billingservice.dto.MoneyMovementRequestDTO;
import com.pm.billingservice.dto.OpenAccountRequestDTO;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Concurrent credits and debits against a <b>single</b> account — the hot-account shape.
 *
 * <p><b>Why this test exists.</b> It did not, and that is how a real defect reached this far.
 * {@code TransferServiceImpl} and {@code PayoutServiceImpl} both declare
 * {@code @Transactional(isolation = READ_COMMITTED)} and both explain why; {@code credit}/{@code debit}
 * — the oldest and most-used money path — was left on MariaDB's default REPEATABLE READ. Under
 * REPEATABLE READ the transaction snapshots at its first read (the idempotency-key lookup), so a
 * later {@code SELECT … FOR UPDATE} on a row a concurrent credit has already committed fails with
 * error 1020 ("Record has changed since last read") instead of waiting for the lock. The pessimistic
 * lock was meant to make concurrent movements <em>serialize</em>; without READ COMMITTED they
 * <em>error</em>.
 *
 * <p><b>How it was found:</b> the Arc C write-path load test. Twelve concurrent credits to one
 * account returned 4×200 and 8×500. The transfer path never showed it because transfers between
 * <em>different</em> account pairs rarely collide, and the one concurrency test that existed was
 * written for lock <em>ordering</em> (deadlock), not for contention on one row.
 *
 * <p><b>Why a single account is the right fixture</b>, not a stylistic choice: contention is the
 * entire subject. Spread the same load across distinct accounts and the locks never meet, every
 * assertion passes, and the test is worthless — the exact trap the outbox claim-lease regression test
 * fell into (seeding as many rows as it claimed, so the scan never locked the supremum gap). The
 * failure mode here is a <em>race</em>, so the test must force the race.
 */
@DisplayName("Concurrent money movement on one account (integration)")
class ConcurrentMoneyMovementIntegrationTest extends AbstractIntegrationTest {

    private static final String ACCOUNTS = "/api/v1/billing-accounts";

    /**
     * Enough threads to guarantee overlap inside the transaction window. The load test saw 500s from
     * 12 concurrent requests; 10 reliably reproduces it while staying well inside the Hikari pool
     * (8 primary connections) so a pool timeout can't be mistaken for the isolation bug.
     */
    private static final int CONCURRENCY = 10;

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetData() {
        // Archive first: it keeps an FK to billing_accounts across the hot/cold seam.
        jdbcTemplate.execute("DELETE FROM ledger_entries_archive");
        jdbcTemplate.execute("DELETE FROM ledger_entries");
        jdbcTemplate.execute("DELETE FROM transfers");
        jdbcTemplate.execute("DELETE FROM idempotency_keys");
        jdbcTemplate.execute("DELETE FROM billing_accounts");
    }

    @Test
    @Timeout(120)
    @DisplayName("concurrent credits to one account all succeed and sum exactly — no 500s, no lost update")
    void concurrentCreditsToOneAccountAllApply() throws Exception {
        UUID account = openAccount();

        List<Integer> statuses = runConcurrently(
                i -> movementStatus(account, "credit", "1.00", "concurrent-credit-" + i));

        // The headline regression. Before READ_COMMITTED this came back as a mix of 200 and 500.
        assertThat(statuses)
                .as("a contended account must serialize, not error — 500 here is MariaDB 1020 "
                        + "surfacing through the global handler")
                .containsOnly(200);

        // Serializing correctly is not enough: the balance must also be right. A lost update would
        // keep every status 200 while quietly dropping money, so assert the arithmetic too.
        assertThat(balanceOf(account))
                .as("%d credits of 1.00 each, applied exactly once", CONCURRENCY)
                .isEqualByComparingTo(new BigDecimal(CONCURRENCY).setScale(2));

        assertThat(ledgerCount(account))
                .as("one ledger entry per applied movement — the audit trail must match the balance")
                .isEqualTo(CONCURRENCY);
    }

    @Test
    @Timeout(120)
    @DisplayName("concurrent debits to one account all succeed and never overdraw")
    void concurrentDebitsToOneAccountAllApply() throws Exception {
        UUID account = openAccount();
        // Fund it with exactly the amount about to be withdrawn, so the final balance is zero and an
        // overdraft would show up as a negative rather than being absorbed by a buffer.
        creditOnce(account, new BigDecimal(CONCURRENCY).setScale(2), "seed");

        List<Integer> statuses = runConcurrently(
                i -> movementStatus(account, "debit", "1.00", "concurrent-debit-" + i));

        assertThat(statuses).as("contended debits must serialize, not error").containsOnly(200);
        assertThat(balanceOf(account))
                .as("funds exactly exhausted — never negative, which is what a lost update looks like")
                .isEqualByComparingTo("0.00");
    }

    @Test
    @Timeout(120)
    @DisplayName("the same Idempotency-Key sent concurrently applies the movement exactly once")
    void sameKeyConcurrentlyAppliesOnce() throws Exception {
        UUID account = openAccount();

        // One key, many simultaneous senders — a client retrying through a flaky network. Permitted
        // outcomes are 200 (applied, or replayed from the captured response) and 409 (a duplicate
        // still in flight). What must NOT happen is the money moving twice.
        List<Integer> statuses =
                runConcurrently(i -> movementStatus(account, "credit", "5.00", "single-key"));

        assertThat(statuses)
                .as("200 = applied or replayed, 409 = duplicate in flight; anything else is a defect")
                .allMatch(status -> status == 200 || status == 409);
        assertThat(statuses).as("at least one caller must have been served").contains(200);

        assertThat(balanceOf(account))
                .as("the domain's unique idempotency_key column is the backstop: applied ONCE, "
                        + "however many callers raced")
                .isEqualByComparingTo("5.00");
        assertThat(ledgerCount(account)).as("exactly one ledger entry").isEqualTo(1);
    }

    /** Fires {@link #CONCURRENCY} requests at once and returns their HTTP statuses. */
    private List<Integer> runConcurrently(RequestByIndex request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < CONCURRENCY; i++) {
                int index = i;
                tasks.add(() -> request.execute(index));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : pool.invokeAll(tasks, 90, TimeUnit.SECONDS)) {
                statuses.add(future.get());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface RequestByIndex {
        Integer execute(int index) throws Exception;
    }

    private UUID openAccount() {
        return rest.postForEntity(ACCOUNTS, new OpenAccountRequestDTO(UUID.randomUUID(), "USD"),
                        BillingAccountResponseDTO.class)
                .getBody()
                .id();
    }

    private void creditOnce(UUID accountId, BigDecimal amount, String key) {
        rest.postForEntity(ACCOUNTS + "/{id}/credit", movement(amount, key), String.class, accountId);
    }

    /**
     * Posts a movement and returns only the status — deliberately deserializing to {@code String} so
     * a {@code problem+json} error body cannot throw inside the worker thread and be reported as a
     * test error rather than the status it actually is.
     */
    private int movementStatus(UUID accountId, String operation, String amount, String key) {
        return rest.postForEntity(ACCOUNTS + "/{id}/" + operation, movement(new BigDecimal(amount), key),
                        String.class, accountId)
                .getStatusCode()
                .value();
    }

    private HttpEntity<MoneyMovementRequestDTO> movement(BigDecimal amount, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        return new HttpEntity<>(new MoneyMovementRequestDTO(amount, null), headers);
    }

    private BigDecimal balanceOf(UUID id) {
        return rest.getForEntity(ACCOUNTS + "/{id}", BillingAccountResponseDTO.class, id).getBody().balance();
    }

    private int ledgerCount(UUID accountId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE account_id = ?", Integer.class, accountId);
    }
}
