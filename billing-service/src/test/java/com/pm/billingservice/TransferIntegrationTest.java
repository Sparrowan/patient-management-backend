package com.pm.billingservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.pm.billingservice.dto.BillingAccountResponseDTO;
import com.pm.billingservice.dto.MoneyMovementRequestDTO;
import com.pm.billingservice.dto.OpenAccountRequestDTO;
import com.pm.billingservice.dto.TransferRequestDTO;
import com.pm.billingservice.dto.TransferResponseDTO;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end transfer tests over real HTTP against a real MariaDB. Exercises the money movement,
 * double-entry ledger, idempotency, and — the point of a transfer vs. a single credit/debit —
 * deadlock-safe concurrency under opposing transfers.
 */
@DisplayName("Transfers (integration)")
class TransferIntegrationTest extends AbstractIntegrationTest {

    private static final String ACCOUNTS = "/api/v1/billing-accounts";
    private static final String TRANSFERS = "/api/v1/billing-accounts/transfers";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetData() {
        jdbcTemplate.execute("DELETE FROM ledger_entries");
        jdbcTemplate.execute("DELETE FROM transfers");
        jdbcTemplate.execute("DELETE FROM billing_accounts");
    }

    private UUID openAccount(String currency) {
        return rest.postForEntity(ACCOUNTS, new OpenAccountRequestDTO(UUID.randomUUID(), currency),
                        BillingAccountResponseDTO.class)
                .getBody()
                .id();
    }

    private void credit(UUID accountId, String amount, String key) {
        rest.postForEntity(ACCOUNTS + "/{id}/credit", movement(amount, key),
                BillingAccountResponseDTO.class, accountId);
    }

    private HttpEntity<MoneyMovementRequestDTO> movement(String amount, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        return new HttpEntity<>(new MoneyMovementRequestDTO(new BigDecimal(amount), null), headers);
    }

    private ResponseEntity<TransferResponseDTO> transfer(UUID from, UUID to, String amount, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        HttpEntity<TransferRequestDTO> body = new HttpEntity<>(
                new TransferRequestDTO(from, to, new BigDecimal(amount), "test transfer"), headers);
        return rest.postForEntity(TRANSFERS, body, TransferResponseDTO.class);
    }

    private BigDecimal balanceOf(UUID id) {
        return rest.getForEntity(ACCOUNTS + "/{id}", BillingAccountResponseDTO.class, id).getBody().balance();
    }

    /** Posts a transfer and returns just the HTTP status — never deserializes the body, so an error
     *  response (problem+json) doesn't blow up the caller; used by the concurrency test. */
    private int transferStatus(UUID from, UUID to, String amount, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        HttpEntity<TransferRequestDTO> body = new HttpEntity<>(
                new TransferRequestDTO(from, to, new BigDecimal(amount), null), headers);
        return rest.postForEntity(TRANSFERS, body, String.class).getStatusCode().value();
    }

    @Test
    @DisplayName("moves money and writes a balanced double-entry pair linked by transfer id")
    void transferMovesMoneyAndWritesDoubleEntry() {
        UUID a = openAccount("USD");
        UUID b = openAccount("USD");
        credit(a, "100.00", "seed-a");

        ResponseEntity<TransferResponseDTO> response = transfer(a, b, "30.00", "t1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().amount()).isEqualByComparingTo("30.00");
        assertThat(balanceOf(a)).isEqualByComparingTo("70.00");
        assertThat(balanceOf(b)).isEqualByComparingTo("30.00");

        // Double-entry: exactly one DEBIT on the source and one CREDIT on the destination, both tagged
        // with this transfer's id.
        UUID transferId = response.getBody().id();
        List<Map<String, Object>> legs = jdbcTemplate.queryForList(
                "SELECT account_id, type, amount FROM ledger_entries WHERE transfer_id = ? ORDER BY type",
                transferId);
        assertThat(legs).hasSize(2);
        assertThat(legs.get(0).get("type")).isEqualTo("CREDIT");
        assertThat(legs.get(1).get("type")).isEqualTo("DEBIT");
    }

    @Test
    @DisplayName("a retried transfer with the same Idempotency-Key applies only once")
    void transferIsIdempotent() {
        UUID a = openAccount("USD");
        UUID b = openAccount("USD");
        credit(a, "100.00", "seed-a");

        transfer(a, b, "30.00", "same-key");
        transfer(a, b, "30.00", "same-key");

        assertThat(balanceOf(a)).isEqualByComparingTo("70.00"); // once, not 40.00
        assertThat(balanceOf(b)).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("transferring more than the source holds returns 422")
    void insufficientFundsReturns422() {
        UUID a = openAccount("USD");
        UUID b = openAccount("USD");
        credit(a, "10.00", "seed-a");

        ResponseEntity<String> response = rest.postForEntity(TRANSFERS,
                new HttpEntity<>(new TransferRequestDTO(a, b, new BigDecimal("50.00"), null), jsonWithKey("t1")),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("transferring between different currencies returns 422")
    void currencyMismatchReturns422() {
        UUID usd = openAccount("USD");
        UUID eur = openAccount("EUR");
        credit(usd, "100.00", "seed");

        ResponseEntity<String> response = rest.postForEntity(TRANSFERS,
                new HttpEntity<>(new TransferRequestDTO(usd, eur, new BigDecimal("10.00"), null), jsonWithKey("t1")),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("transferring to the same account returns 400")
    void sameAccountReturns400() {
        UUID a = openAccount("USD");
        credit(a, "100.00", "seed");

        ResponseEntity<String> response = rest.postForEntity(TRANSFERS,
                new HttpEntity<>(new TransferRequestDTO(a, a, new BigDecimal("10.00"), null), jsonWithKey("t1")),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("concurrent opposing transfers conserve total money and never deadlock (lock ordering)")
    void concurrentOpposingTransfersAreSafe() throws Exception {
        UUID a = openAccount("USD");
        UUID b = openAccount("USD");
        credit(a, "1000.00", "seed-a");
        credit(b, "1000.00", "seed-b");

        int perDirection = 5;
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Callable<Integer>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < perDirection; i++) {
            String ab = "ab-" + i;
            String ba = "ba-" + i;
            tasks.add(() -> transferStatus(a, b, "10.00", ab)); // A -> B
            tasks.add(() -> transferStatus(b, a, "10.00", ba)); // B -> A (opposing)
        }

        List<Future<Integer>> results = pool.invokeAll(tasks, 60, TimeUnit.SECONDS);
        pool.shutdown();

        for (Future<Integer> result : results) {
            assertThat(result.get()).as("every transfer should succeed (no deadlock)").isEqualTo(201);
        }
        // Equal opposing flows net to zero, and — crucially — no money is created or lost.
        assertThat(balanceOf(a).add(balanceOf(b))).isEqualByComparingTo("2000.00");
        assertThat(balanceOf(a)).isEqualByComparingTo("1000.00");
        assertThat(balanceOf(b)).isEqualByComparingTo("1000.00");
    }

    private HttpHeaders jsonWithKey(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        return headers;
    }
}
