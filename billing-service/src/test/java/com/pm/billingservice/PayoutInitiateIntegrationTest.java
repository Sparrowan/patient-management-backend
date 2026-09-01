package com.pm.billingservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.pm.billingservice.dto.BillingAccountResponseDTO;
import com.pm.billingservice.dto.MoneyMovementRequestDTO;
import com.pm.billingservice.dto.OpenAccountRequestDTO;
import com.pm.billingservice.dto.PayoutRequestDTO;
import com.pm.billingservice.dto.PayoutResponseDTO;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
 * End-to-end tests for the synchronous first step of the payout saga: an initiate debits the source
 * account, records the payout as PENDING, and writes a single DEBIT ledger leg — all in one local
 * transaction — and is idempotent. (The async settlement/compensation come in later bits.)
 */
@DisplayName("Payout initiate (integration)")
class PayoutInitiateIntegrationTest extends AbstractIntegrationTest {

    private static final String ACCOUNTS = "/api/v1/billing-accounts";
    private static final String PAYOUTS = "/api/v1/billing-accounts/payouts";
    private static final String DEST = "DE89370400440532013000";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetData() {
        jdbcTemplate.execute("DELETE FROM ledger_entries");
        jdbcTemplate.execute("DELETE FROM payouts");
        jdbcTemplate.execute("DELETE FROM idempotency_keys");
        jdbcTemplate.execute("DELETE FROM billing_accounts");
    }

    private UUID openAccount(String currency) {
        return rest.postForEntity(ACCOUNTS, new OpenAccountRequestDTO(UUID.randomUUID(), currency),
                        BillingAccountResponseDTO.class)
                .getBody()
                .id();
    }

    private void credit(UUID accountId, String amount, String key) {
        HttpHeaders headers = jsonWithKey(key);
        rest.postForEntity(ACCOUNTS + "/{id}/credit",
                new HttpEntity<>(new MoneyMovementRequestDTO(new BigDecimal(amount), null), headers),
                BillingAccountResponseDTO.class, accountId);
    }

    private ResponseEntity<PayoutResponseDTO> payout(UUID from, String amount, String key) {
        HttpEntity<PayoutRequestDTO> body = new HttpEntity<>(
                new PayoutRequestDTO(from, DEST, new BigDecimal(amount), "rent"), jsonWithKey(key));
        return rest.postForEntity(PAYOUTS, body, PayoutResponseDTO.class);
    }

    private BigDecimal balanceOf(UUID id) {
        return rest.getForEntity(ACCOUNTS + "/{id}", BillingAccountResponseDTO.class, id).getBody().balance();
    }

    @Test
    @DisplayName("debits the source, returns 202 PENDING, and writes one DEBIT leg linked by payout id")
    void initiateDebitsAndRecordsPending() {
        UUID a = openAccount("USD");
        credit(a, "100.00", "seed-a");

        ResponseEntity<PayoutResponseDTO> response = payout(a, "30.00", "p1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().status().name()).isEqualTo("PENDING");
        assertThat(response.getBody().amount()).isEqualByComparingTo("30.00");
        assertThat(balanceOf(a)).isEqualByComparingTo("70.00"); // debited up front

        UUID payoutId = response.getBody().id();
        List<Map<String, Object>> legs = jdbcTemplate.queryForList(
                "SELECT type, amount FROM ledger_entries WHERE payout_id = ?", payoutId);
        assertThat(legs).hasSize(1);
        assertThat(legs.get(0).get("type")).isEqualTo("DEBIT");
    }

    @Test
    @DisplayName("a retried initiate with the same Idempotency-Key debits only once")
    void initiateIsIdempotent() {
        UUID a = openAccount("USD");
        credit(a, "100.00", "seed-a");

        payout(a, "30.00", "same-key");
        payout(a, "30.00", "same-key");

        assertThat(balanceOf(a)).isEqualByComparingTo("70.00"); // once, not 40.00
    }

    @Test
    @DisplayName("paying out more than the source holds returns 422")
    void insufficientFundsReturns422() {
        UUID a = openAccount("USD");
        credit(a, "10.00", "seed-a");

        ResponseEntity<String> response = rest.postForEntity(PAYOUTS,
                new HttpEntity<>(new PayoutRequestDTO(a, DEST, new BigDecimal("50.00"), null), jsonWithKey("p1")),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private HttpHeaders jsonWithKey(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        return headers;
    }
}
