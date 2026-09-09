package com.pm.patientservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.pm.patientservice.AbstractIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Retention of the transactional outbox against a real MariaDB. The point of these tests is the
 * <em>safety predicate</em>: pruning must never remove work that hasn't happened yet.
 */
@DisplayName("Outbox retention (integration)")
class OutboxRetentionIntegrationTest extends AbstractIntegrationTest {

    @Autowired private OutboxRetentionWorker worker;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetOutbox() {
        jdbcTemplate.execute("DELETE FROM outbox_events");
    }

    /** Inserts a row directly so we can control created_at/published_at precisely. */
    private UUID insertEvent(Instant createdAt, Instant publishedAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, topic, "
                        + "payload, created_at, published_at, attempts) "
                        + "VALUES (?, 'Patient', ?, 'PatientRegistered', 'patient-events', '{}', ?, ?, 0)",
                id, UUID.randomUUID(), java.sql.Timestamp.from(createdAt),
                publishedAt == null ? null : java.sql.Timestamp.from(publishedAt));
        return id;
    }

    private List<UUID> remainingIds() {
        return jdbcTemplate.queryForList("SELECT id FROM outbox_events", UUID.class);
    }

    @Test
    @DisplayName("prunes published rows past the retention window, keeps recent ones")
    void prunesOldPublishedRows() {
        Instant old = Instant.now().minus(OutboxRetentionWorker.RETENTION).minus(1, ChronoUnit.DAYS);
        Instant recent = Instant.now().minus(1, ChronoUnit.HOURS);
        UUID stale = insertEvent(old, old);
        UUID fresh = insertEvent(recent, recent);

        int deleted = worker.purgeExpired();

        assertThat(deleted).isEqualTo(1);
        assertThat(remainingIds()).containsExactly(fresh).doesNotContain(stale);
    }

    @Test
    @DisplayName("NEVER prunes an unpublished row, however old — it is still pending work")
    void neverPrunesUnpublishedRows() {
        Instant ancient = Instant.now().minus(365, ChronoUnit.DAYS);
        UUID pending = insertEvent(ancient, null); // published_at IS NULL → not yet sent

        int deleted = worker.purgeExpired();

        assertThat(deleted).isZero();
        assertThat(remainingIds()).containsExactly(pending);
    }

    @Test
    @DisplayName("drains a backlog larger than one batch")
    void drainsMultipleBatches() {
        Instant old = Instant.now().minus(OutboxRetentionWorker.RETENTION).minus(1, ChronoUnit.DAYS);
        int count = OutboxRetentionWorker.BATCH_SIZE + 25; // forces a second batch
        for (int i = 0; i < count; i++) {
            insertEvent(old, old);
        }

        int deleted = worker.purgeExpired();

        assertThat(deleted).isEqualTo(count);
        assertThat(remainingIds()).isEmpty();
    }

    @Test
    @DisplayName("is a no-op when there is nothing expired")
    void noOpWhenNothingExpired() {
        insertEvent(Instant.now(), Instant.now());

        assertThat(worker.purgeExpired()).isZero();
        assertThat(remainingIds()).hasSize(1);
    }
}
