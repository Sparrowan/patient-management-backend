package com.pm.billingservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.pm.billingservice.dto.BillingAccountResponseDTO;
import com.pm.billingservice.dto.MoneyMovementRequestDTO;
import com.pm.billingservice.dto.OpenAccountRequestDTO;
import com.pm.billingservice.dto.PayoutRequestDTO;
import com.pm.billingservice.dto.PayoutResponseDTO;
import com.pm.billingservice.payout.PayoutSagaWorker;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Drives the saga worker against a real MariaDB (the periodic trigger is disabled in tests — see
 * {@link AbstractIntegrationTest} — so the worker is invoked directly and deterministically). Proves
 * the native SKIP-LOCKED claim query works and that each settlement outcome moves the payout to the
 * right terminal/retry state, including compensation: a declined payout is REVERSED and the debit is
 * credited back to the source account (a transient error instead stays PENDING for retry).
 */
@DisplayName("Payout settlement worker (integration)")
class PayoutSettlementIntegrationTest extends AbstractIntegrationTest {

    private static final String ACCOUNTS = "/api/v1/billing-accounts";
    private static final String PAYOUTS = "/api/v1/billing-accounts/payouts";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PayoutSagaWorker worker;

    @BeforeEach
    void resetData() {
        jdbcTemplate.execute("DELETE FROM ledger_entries");
        jdbcTemplate.execute("DELETE FROM payouts");
        jdbcTemplate.execute("DELETE FROM idempotency_keys");
        jdbcTemplate.execute("DELETE FROM billing_accounts");
    }

    private UUID openAccount() {
        return rest.postForEntity(ACCOUNTS, new OpenAccountRequestDTO(UUID.randomUUID(), "USD"),
                BillingAccountResponseDTO.class).getBody().id();
    }

    private void credit(UUID accountId, String amount, String key) {
        rest.postForEntity(ACCOUNTS + "/{id}/credit",
                new HttpEntity<>(new MoneyMovementRequestDTO(new BigDecimal(amount), null), jsonWithKey(key)),
                BillingAccountResponseDTO.class, accountId);
    }

    private UUID initiatePayout(UUID from, String destination, String key) {
        return rest.postForEntity(PAYOUTS,
                new HttpEntity<>(new PayoutRequestDTO(from, destination, new BigDecimal("30.00"), "rent"),
                        jsonWithKey(key)),
                PayoutResponseDTO.class).getBody().id();
    }

    private String statusOf(UUID payoutId) {
        return jdbcTemplate.queryForObject("SELECT status FROM payouts WHERE id = ?", String.class, payoutId);
    }

    private int attemptsOf(UUID payoutId) {
        return jdbcTemplate.queryForObject("SELECT attempts FROM payouts WHERE id = ?", Integer.class, payoutId);
    }

    private BigDecimal balanceOf(UUID id) {
        return rest.getForEntity(ACCOUNTS + "/{id}", BillingAccountResponseDTO.class, id).getBody().balance();
    }

    @Test
    @DisplayName("settles a PENDING payout to COMPLETED, leaving the debit in place")
    void settlesToCompleted() {
        UUID account = openAccount();
        credit(account, "100.00", "seed");
        UUID payout = initiatePayout(account, "DE89370400440532013000", "p1");

        worker.drivePendingPayouts();

        assertThat(statusOf(payout)).isEqualTo("COMPLETED");
        assertThat(balanceOf(account)).isEqualByComparingTo("70.00"); // debited at initiate, still gone
    }

    @Test
    @DisplayName("compensates a declined payout to REVERSED, crediting the debit back")
    void reversesDeclinedPayout() {
        UUID account = openAccount();
        credit(account, "100.00", "seed");
        UUID payout = initiatePayout(account, "FAIL-DE89370400440532013000", "p1");
        assertThat(balanceOf(account)).isEqualByComparingTo("70.00"); // debited at initiate

        worker.drivePendingPayouts();

        assertThat(statusOf(payout)).isEqualTo("REVERSED");
        assertThat(balanceOf(account)).isEqualByComparingTo("100.00"); // debit credited back
    }

    @Test
    @DisplayName("keeps a transiently-failing payout PENDING and records a retry attempt")
    void retriesTransientFailure() {
        UUID account = openAccount();
        credit(account, "100.00", "seed");
        UUID payout = initiatePayout(account, "RETRY-DE89370400440532013000", "p1");

        worker.drivePendingPayouts();

        assertThat(statusOf(payout)).isEqualTo("PENDING");
        assertThat(attemptsOf(payout)).isEqualTo(1);
    }

    private HttpHeaders jsonWithKey(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        return headers;
    }
}
