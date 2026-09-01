package com.pm.billingservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.pm.billingservice.dto.BillingAccountResponseDTO;
import com.pm.billingservice.dto.MoneyMovementRequestDTO;
import com.pm.billingservice.dto.OpenAccountRequestDTO;
import java.math.BigDecimal;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end tests for the generic HTTP idempotency layer over real HTTP against a real MariaDB.
 * Exercises the interceptor + store on the {@code credit} endpoint (the proving-ground @Idempotent
 * handler): replay of the original response, key-reuse rejection, the required header, and the
 * exactly-once guarantee under concurrent duplicates.
 */
@DisplayName("HTTP idempotency (integration)")
class IdempotencyIntegrationTest extends AbstractIntegrationTest {

    private static final String ACCOUNTS = "/api/v1/billing-accounts";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetData() {
        jdbcTemplate.execute("DELETE FROM ledger_entries");
        jdbcTemplate.execute("DELETE FROM idempotency_keys");
        jdbcTemplate.execute("DELETE FROM billing_accounts");
    }

    @Test
    @DisplayName("a retried credit with the same key replays the original response and applies once")
    void sameKeyReplaysAndAppliesOnce() {
        UUID account = openAccount("USD");

        ResponseEntity<String> first = credit(account, "50.00", "k1");
        ResponseEntity<String> second = credit(account, "50.00", "k1");

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        // The replay is byte-for-byte the original response, flagged for the client/observability.
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        // Money moved once, and only one ledger entry exists.
        assertThat(balanceOf(account)).isEqualByComparingTo("50.00");
        assertThat(ledgerCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("reusing a key for a different body returns 422 and does not apply")
    void keyReuseWithDifferentBodyReturns422() {
        UUID account = openAccount("USD");

        assertThat(credit(account, "50.00", "k2").getStatusCode().value()).isEqualTo(200);
        // Same key, different amount → different fingerprint → client bug.
        assertThat(credit(account, "75.00", "k2").getStatusCode().value()).isEqualTo(422);

        assertThat(balanceOf(account)).isEqualByComparingTo("50.00"); // second never applied
        assertThat(ledgerCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("an @Idempotent endpoint without an Idempotency-Key returns 400")
    void missingKeyReturns400() {
        UUID account = openAccount("USD");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<MoneyMovementRequestDTO> body =
                new HttpEntity<>(new MoneyMovementRequestDTO(new BigDecimal("10.00"), null), headers);

        ResponseEntity<String> response =
                rest.postForEntity(ACCOUNTS + "/{id}/credit", body, String.class, account);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(ledgerCount()).isZero();
    }

    @Test
    @DisplayName("concurrent duplicates apply exactly once (and never 5xx)")
    void concurrentDuplicatesApplyOnce() throws Exception {
        UUID account = openAccount("USD");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Integer> call = () -> credit(account, "40.00", "k3").getStatusCode().value();
        List<Future<Integer>> results = pool.invokeAll(List.of(call, call));
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        for (Future<Integer> f : results) {
            int status = f.get();
            // Each duplicate is either applied/replayed (200) or rejected as in-flight (409) — never a 5xx.
            assertThat(status).isIn(200, 409);
        }
        assertThat(balanceOf(account)).isEqualByComparingTo("40.00"); // once, not 80.00
        assertThat(ledgerCount()).isEqualTo(1);
    }

    private UUID openAccount(String currency) {
        return rest.postForEntity(ACCOUNTS, new OpenAccountRequestDTO(UUID.randomUUID(), currency),
                        BillingAccountResponseDTO.class)
                .getBody()
                .id();
    }

    private ResponseEntity<String> credit(UUID accountId, String amount, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        HttpEntity<MoneyMovementRequestDTO> body =
                new HttpEntity<>(new MoneyMovementRequestDTO(new BigDecimal(amount), null), headers);
        return rest.postForEntity(ACCOUNTS + "/{id}/credit", body, String.class, accountId);
    }

    private BigDecimal balanceOf(UUID id) {
        return rest.getForEntity(ACCOUNTS + "/{id}", BillingAccountResponseDTO.class, id).getBody().balance();
    }

    private int ledgerCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_entries", Integer.class);
    }
}
