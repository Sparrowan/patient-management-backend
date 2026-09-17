package com.pm.billingservice.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.pm.billingservice.AbstractIntegrationTest;
import com.pm.billingservice.model.LedgerRecord;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Archiving against a real MariaDB. These tests are about the two invariants every ledger read
 * depends on: an entry is in exactly one table, and the archive holds a time-ordered <em>prefix</em>
 * of history.
 */
@DisplayName("Ledger archival (integration)")
class LedgerArchiveIntegrationTest extends AbstractIntegrationTest {

    private static final int RETENTION_MONTHS = 13;

    @Autowired private LedgerArchiveWorker worker;
    @Autowired private LedgerEntryLookup lookup;
    @Autowired private LedgerHistoryReader history;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID accountId;

    @BeforeEach
    void resetLedger() {
        jdbcTemplate.execute("DELETE FROM ledger_entries_archive");
        jdbcTemplate.execute("DELETE FROM ledger_entries");
        jdbcTemplate.execute("DELETE FROM payouts");
        jdbcTemplate.execute("DELETE FROM billing_accounts");
        accountId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO billing_accounts (id, patient_id, status, balance, currency, created_at, "
                        + "updated_at, version) VALUES (?, ?, 'ACTIVE', 0.00, 'USD', ?, ?, 0)",
                accountId, UUID.randomUUID(), ts(Instant.now()), ts(Instant.now()));
    }

    private static java.sql.Timestamp ts(Instant instant) {
        return java.sql.Timestamp.from(instant);
    }

    private Instant aged(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }

    /** Inserts an entry directly so created_at is exact. Returns its id. */
    private UUID insertEntry(String key, Instant createdAt, UUID payoutId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO ledger_entries (id, account_id, type, amount, balance_after, "
                        + "idempotency_key, description, transfer_id, payout_id, created_at, updated_at, "
                        + "created_by, updated_by, version) "
                        + "VALUES (?, ?, 'CREDIT', 10.00, 10.00, ?, 'seed', NULL, ?, ?, ?, 'tester', 'tester', 0)",
                id, accountId, key, payoutId, ts(createdAt), ts(createdAt));
        return id;
    }

    private UUID insertPayout(String status, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO payouts (id, source_account_id, destination_reference, amount, currency, "
                        + "status, idempotency_key, created_at, updated_at, version) "
                        + "VALUES (?, ?, 'ext-acct', 10.00, 'USD', ?, ?, ?, ?, 0)",
                id, accountId, status, "payout-" + id, ts(createdAt), ts(createdAt));
        return id;
    }

    private List<UUID> idsIn(String table) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM " + table + " ORDER BY created_at ASC, id ASC", UUID.class);
    }

    @Test
    @DisplayName("moves aged entries and leaves recent ones hot")
    void movesAgedEntriesOnly() {
        UUID old = insertEntry("k-old", aged(RETENTION_MONTHS * 30 + 10), null);
        UUID recent = insertEntry("k-recent", aged(5), null);

        assertThat(worker.archiveExpired()).isEqualTo(1);

        assertThat(idsIn("ledger_entries")).containsExactly(recent);
        assertThat(idsIn("ledger_entries_archive")).containsExactly(old);
    }

    @Test
    @DisplayName("an archived entry is in exactly one table, and still found by idempotency key")
    void archivedEntryIsStillFoundByKey() {
        insertEntry("k-moved", aged(RETENTION_MONTHS * 30 + 10), null);

        worker.archiveExpired();

        // The whole point of the two-table lookup: a retry against an archived key must still replay.
        assertThat(lookup.findByIdempotencyKey("k-moved"))
                .isPresent()
                .get()
                .satisfies(entry -> assertThat(entry.getIdempotencyKey()).isEqualTo("k-moved"));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ledger_entries WHERE idempotency_key = 'k-moved'", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("preserves the original audit columns — the move must not re-stamp who or when")
    void preservesAuditColumns() {
        Instant createdAt = aged(RETENTION_MONTHS * 30 + 10).truncatedTo(ChronoUnit.SECONDS);
        insertEntry("k-audit", createdAt, null);

        worker.archiveExpired();

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT created_by FROM ledger_entries_archive WHERE idempotency_key = 'k-audit'",
                        String.class))
                .isEqualTo("tester");
        assertThat(jdbcTemplate
                        .queryForObject(
                                "SELECT created_at FROM ledger_entries_archive WHERE idempotency_key = 'k-audit'",
                                java.sql.Timestamp.class)
                        .toInstant())
                .isEqualTo(createdAt);
        // archived_at is the move's own timestamp, and must be far newer than the movement itself.
        assertThat(jdbcTemplate
                        .queryForObject(
                                "SELECT archived_at FROM ledger_entries_archive WHERE idempotency_key = 'k-audit'",
                                java.sql.Timestamp.class)
                        .toInstant())
                .isAfter(createdAt);
    }

    @Test
    @DisplayName("STOPS at an unsettled payout rather than skipping past it — the archive stays a prefix")
    void haltsAtUnsettledPayout() {
        Instant blockedAt = aged(RETENTION_MONTHS * 30 + 50);
        UUID pendingPayout = insertPayout("PENDING", blockedAt);
        UUID blocked = insertEntry("k-blocked", blockedAt, pendingPayout);
        // Older than the blocked entry, so still archivable...
        UUID older = insertEntry("k-older", aged(RETENTION_MONTHS * 30 + 60), null);
        // ...and younger than it, so it must stay hot even though it is past the age cutoff.
        UUID younger = insertEntry("k-younger", aged(RETENTION_MONTHS * 30 + 40), null);

        assertThat(worker.archiveExpired()).isEqualTo(1);

        assertThat(idsIn("ledger_entries_archive")).containsExactly(older);
        assertThat(idsIn("ledger_entries")).containsExactly(blocked, younger);
    }

    @Test
    @DisplayName("resumes once the payout settles — holding back is self-healing, not a dead end")
    void resumesOncePayoutSettles() {
        Instant blockedAt = aged(RETENTION_MONTHS * 30 + 50);
        UUID payout = insertPayout("PENDING", blockedAt);
        insertEntry("k-blocked", blockedAt, payout);
        insertEntry("k-younger", aged(RETENTION_MONTHS * 30 + 40), null);
        worker.archiveExpired();
        assertThat(idsIn("ledger_entries")).hasSize(2);

        jdbcTemplate.update("UPDATE payouts SET status = 'COMPLETED' WHERE id = ?", payout);

        assertThat(worker.archiveExpired()).isEqualTo(2);
        assertThat(idsIn("ledger_entries")).isEmpty();
        assertThat(idsIn("ledger_entries_archive")).hasSize(2);
    }

    @Test
    @DisplayName("drains a backlog larger than one batch, oldest first")
    void drainsMultipleBatches() {
        int count = LedgerArchiveWorker.BATCH_SIZE + 25;
        for (int i = 0; i < count; i++) {
            insertEntry("k-" + i, aged(RETENTION_MONTHS * 30 + 10 + i), null);
        }

        assertThat(worker.archiveExpired()).isEqualTo(count);
        assertThat(idsIn("ledger_entries")).isEmpty();
        assertThat(idsIn("ledger_entries_archive")).hasSize(count);
    }

    @Test
    @DisplayName("is a no-op when nothing has aged out")
    void noOpWhenNothingAged() {
        insertEntry("k-fresh", aged(1), null);

        assertThat(worker.archiveExpired()).isZero();
        assertThat(idsIn("ledger_entries")).hasSize(1);
        assertThat(idsIn("ledger_entries_archive")).isEmpty();
    }

    @Test
    @DisplayName("account history reads unbroken across the seam, newest first")
    void historySpansTheSeam() {
        insertEntry("k-1", aged(RETENTION_MONTHS * 30 + 20), null);
        insertEntry("k-2", aged(RETENTION_MONTHS * 30 + 10), null);
        insertEntry("k-3", aged(2), null);
        worker.archiveExpired();
        assertThat(idsIn("ledger_entries_archive")).hasSize(2);

        var page = history.read(accountId, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent().stream().map(LedgerRecord::getIdempotencyKey))
                .containsExactly("k-3", "k-2", "k-1");
    }

    @Test
    @DisplayName("a page straddling the seam has no gap and no duplicate")
    void pageStraddlesTheSeamCleanly() {
        // Two hot, three archived: a page of 3 must take both hot rows then the newest archived one.
        insertEntry("k-a1", aged(RETENTION_MONTHS * 30 + 30), null);
        insertEntry("k-a2", aged(RETENTION_MONTHS * 30 + 20), null);
        insertEntry("k-a3", aged(RETENTION_MONTHS * 30 + 10), null);
        insertEntry("k-h1", aged(4), null);
        insertEntry("k-h2", aged(2), null);
        worker.archiveExpired();

        var first = history.read(accountId, PageRequest.of(0, 3));
        var second = history.read(accountId, PageRequest.of(1, 3));

        assertThat(first.getContent().stream().map(LedgerRecord::getIdempotencyKey))
                .containsExactly("k-h2", "k-h1", "k-a3");
        assertThat(second.getContent().stream().map(LedgerRecord::getIdempotencyKey))
                .containsExactly("k-a2", "k-a1");
        assertThat(first.getTotalElements()).isEqualTo(5);
    }
}
