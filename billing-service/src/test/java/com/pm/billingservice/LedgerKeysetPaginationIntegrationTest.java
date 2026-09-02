package com.pm.billingservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.pm.billingservice.dto.BillingAccountResponseDTO;
import com.pm.billingservice.dto.MoneyMovementRequestDTO;
import com.pm.billingservice.dto.OpenAccountRequestDTO;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end keyset (cursor) pagination of the ledger over real HTTP + MariaDB. The core guarantee:
 * paging through with a cursor visits every entry exactly once — no gaps, no duplicates — in the
 * same newest-first order as offset paging.
 */
@DisplayName("Ledger keyset pagination (integration)")
class LedgerKeysetPaginationIntegrationTest extends AbstractIntegrationTest {

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
    @DisplayName("paging by cursor visits every entry once, in order, and ends cleanly")
    void pagesThroughWithoutGapsOrDuplicates() {
        UUID account = openAccount();
        // 25 movements → with pageSize 10 that's pages of 10, 10, 5.
        for (int i = 0; i < 25; i++) {
            credit(account, "1.00", "seed-" + i);
        }

        List<UUID> seen = new ArrayList<>();
        Set<UUID> unique = new HashSet<>();
        List<Instant> timestamps = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        boolean hasMore = true;

        while (hasMore) {
            CursorPageView page = fetchPage(account, cursor, 10);
            pages++;
            assertThat(page.items().size()).isLessThanOrEqualTo(10);
            for (LedgerView entry : page.items()) {
                seen.add(entry.id());
                unique.add(entry.id());
                timestamps.add(entry.createdAt());
            }
            hasMore = page.hasMore();
            cursor = page.nextCursor();
            if (hasMore) {
                assertThat(cursor).isNotNull();
            } else {
                assertThat(cursor).isNull(); // last page carries no next cursor
            }
        }

        assertThat(pages).isEqualTo(3);
        assertThat(seen).hasSize(25);          // no gaps: all 25 entries visited
        assertThat(unique).hasSize(25);        // no duplicates across page boundaries
        // Newest-first order holds across pages (created_at non-increasing).
        assertThat(timestamps).isSortedAccordingTo((a, b) -> b.compareTo(a));
    }

    @Test
    @DisplayName("a fresh account with no entries returns an empty final page")
    void emptyLedger() {
        UUID account = openAccount();

        CursorPageView page = fetchPage(account, null, 10);

        assertThat(page.items()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    // --- helpers ---

    private CursorPageView fetchPage(UUID accountId, String cursor, int limit) {
        String url = ACCOUNTS + "/" + accountId + "/ledger/keyset?limit=" + limit
                + (cursor != null ? "&cursor=" + cursor : "");
        ResponseEntity<CursorPageView> response =
                rest.exchange(url, org.springframework.http.HttpMethod.GET, null,
                        new ParameterizedTypeReference<CursorPageView>() {});
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }

    private UUID openAccount() {
        return rest.postForEntity(ACCOUNTS, new OpenAccountRequestDTO(UUID.randomUUID(), "USD"),
                        BillingAccountResponseDTO.class)
                .getBody()
                .id();
    }

    private void credit(UUID accountId, String amount, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        rest.postForEntity(ACCOUNTS + "/{id}/credit",
                new HttpEntity<>(new MoneyMovementRequestDTO(new BigDecimal(amount), null), headers),
                String.class, accountId);
    }

    /** Minimal views to deserialize the JSON without depending on generic-record inference. */
    private record CursorPageView(List<LedgerView> items, String nextCursor, boolean hasMore) {
    }

    private record LedgerView(UUID id, Instant createdAt) {
    }
}
