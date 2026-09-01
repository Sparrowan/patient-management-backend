package com.pm.billingservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.pm.billingservice.dto.BillingAccountResponseDTO;
import com.pm.billingservice.dto.LedgerEntryResponseDTO;
import com.pm.billingservice.dto.MoneyMovementRequestDTO;
import com.pm.billingservice.dto.OpenAccountRequestDTO;
import java.math.BigDecimal;
import java.util.UUID;
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
 * End-to-end integration tests over real HTTP against a real MariaDB (Testcontainers). Exercises
 * Flyway, DECIMAL money mapping, the ledger + FK, and — critically — idempotency and
 * insufficient-funds handling.
 */
@DisplayName("Billing API (integration)")
class BillingAccountIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/billing-accounts";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;
    @org.springframework.boot.test.web.server.LocalServerPort private int port;

    @BeforeEach
    void resetData() {
        // Delete child (ledger) before parent (accounts) to respect the FK.
        jdbcTemplate.execute("DELETE FROM ledger_entries");
        jdbcTemplate.execute("DELETE FROM idempotency_keys");
        jdbcTemplate.execute("DELETE FROM billing_accounts");
    }

    private UUID openAccount() {
        return rest.postForEntity(BASE, new OpenAccountRequestDTO(UUID.randomUUID(), "USD"),
                        BillingAccountResponseDTO.class)
                .getBody()
                .id();
    }

    private HttpEntity<MoneyMovementRequestDTO> movement(String amount, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return new HttpEntity<>(new MoneyMovementRequestDTO(new BigDecimal(amount), null), headers);
    }

    private BigDecimal balanceOf(UUID id) {
        return rest.getForEntity(BASE + "/{id}", BillingAccountResponseDTO.class, id).getBody().balance();
    }

    @Test
    @DisplayName("credit then debit updates balance and appends ledger entries")
    void creditThenDebit() {
        UUID id = openAccount();

        ResponseEntity<LedgerEntryResponseDTO> credit =
                rest.postForEntity(BASE + "/{id}/credit", movement("100.00", "c1"), LedgerEntryResponseDTO.class, id);
        assertThat(credit.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(credit.getBody().balanceAfter()).isEqualByComparingTo("100.00");

        ResponseEntity<LedgerEntryResponseDTO> debit =
                rest.postForEntity(BASE + "/{id}/debit", movement("30.00", "d1"), LedgerEntryResponseDTO.class, id);
        assertThat(debit.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(debit.getBody().balanceAfter()).isEqualByComparingTo("70.00");

        assertThat(balanceOf(id)).isEqualByComparingTo("70.00");

        Integer entries = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE account_id = ?", Integer.class, id);
        assertThat(entries).isEqualTo(2);
    }

    @Test
    @DisplayName("a retried credit with the same Idempotency-Key is applied only once")
    void creditIsIdempotent() {
        UUID id = openAccount();

        rest.postForEntity(BASE + "/{id}/credit", movement("100.00", "same-key"), LedgerEntryResponseDTO.class, id);
        rest.postForEntity(BASE + "/{id}/credit", movement("100.00", "same-key"), LedgerEntryResponseDTO.class, id);

        assertThat(balanceOf(id)).isEqualByComparingTo("100.00"); // once, not 200.00
    }

    @Test
    @DisplayName("debiting more than the balance returns 422")
    void insufficientFunds() {
        UUID id = openAccount(); // balance 0.00
        ResponseEntity<String> debit =
                rest.postForEntity(BASE + "/{id}/debit", movement("50.00", "d1"), String.class, id);
        assertThat(debit.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("a money movement without an Idempotency-Key returns 400")
    void missingIdempotencyKey() {
        UUID id = openAccount();
        ResponseEntity<String> resp =
                rest.postForEntity(BASE + "/{id}/credit", movement("10.00", null), String.class, id);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("open → read by id → read by patient, with a zero BigDecimal balance")
    void openAndRead() {
        UUID patientId = UUID.randomUUID();
        ResponseEntity<BillingAccountResponseDTO> opened = rest.postForEntity(
                BASE, new OpenAccountRequestDTO(patientId, "USD"), BillingAccountResponseDTO.class);
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(opened.getBody().balance()).isEqualByComparingTo("0.00");
        UUID id = opened.getBody().id();

        assertThat(rest.getForEntity(BASE + "/{id}", BillingAccountResponseDTO.class, id).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity(BASE + "/by-patient/{pid}", BillingAccountResponseDTO.class, patientId)
                        .getBody()
                        .id())
                .isEqualTo(id);
    }

    @Test
    @DisplayName("a second account for the same patient is rejected with 409")
    void duplicatePatientRejected() {
        UUID patientId = UUID.randomUUID();
        rest.postForEntity(BASE, new OpenAccountRequestDTO(patientId, "USD"), BillingAccountResponseDTO.class);
        ResponseEntity<String> dup =
                rest.postForEntity(BASE, new OpenAccountRequestDTO(patientId, "EUR"), String.class);
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("invalid currency is rejected with 400")
    void invalidCurrencyRejected() {
        ResponseEntity<String> bad = rest.postForEntity(
                BASE, new OpenAccountRequestDTO(UUID.randomUUID(), "dollars"), String.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("unknown id returns 404")
    void unknownIdReturns404() {
        assertThat(rest.getForEntity(BASE + "/{id}", String.class, UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("requires a token: a request with no Authorization header → 401")
    void unauthenticatedRequestIsRejected() {
        ResponseEntity<String> response =
                new TestRestTemplate().getForEntity("http://localhost:" + port + BASE, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
