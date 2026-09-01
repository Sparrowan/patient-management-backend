package com.pm.billingservice.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for the {@link IdempotencyRecord} lifecycle logic (no Spring, no DB). */
@DisplayName("IdempotencyRecord")
class IdempotencyRecordTest {

    private static final Duration TTL = Duration.ofHours(24);
    private static final Duration LEASE = Duration.ofSeconds(60);

    private IdempotencyRecord started(Instant now) {
        return IdempotencyRecord.start("key", "user", "POST", "/api/v1/x", "fp", now, TTL);
    }

    @Test
    @DisplayName("start() claims IN_PROGRESS with a TTL and no response yet")
    void startClaims() {
        Instant now = Instant.now();
        IdempotencyRecord record = started(now);

        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThat(record.isCompleted()).isFalse();
        assertThat(record.isExpired(now)).isFalse();
        assertThat(record.isExpired(now.plus(TTL).plusSeconds(1))).isTrue();
        assertThat(record.getResponseStatus()).isNull();
    }

    @Test
    @DisplayName("complete() captures the response and becomes COMPLETED")
    void completeCaptures() {
        IdempotencyRecord record = started(Instant.now());

        record.complete(200, "{\"ok\":true}", "application/json");

        assertThat(record.isCompleted()).isTrue();
        assertThat(record.getResponseStatus()).isEqualTo(200);
        assertThat(record.getResponseBody()).isEqualTo("{\"ok\":true}");
        assertThat(record.getResponseContentType()).isEqualTo("application/json");
    }

    @Test
    @DisplayName("a claim is stale only once it outlives the lease")
    void claimStaleAfterLease() {
        Instant now = Instant.now();
        IdempotencyRecord record = started(now);

        assertThat(record.isClaimStale(now.plusSeconds(30), LEASE)).isFalse();
        assertThat(record.isClaimStale(now.plusSeconds(61), LEASE)).isTrue();
    }

    @Test
    @DisplayName("reopen() resets a completed record to a fresh IN_PROGRESS claim")
    void reopenResets() {
        Instant now = Instant.now();
        IdempotencyRecord record = started(now);
        record.complete(200, "body", "application/json");

        Instant later = now.plus(TTL).plusSeconds(5);
        record.reopen("fp2", later, TTL);

        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThat(record.getResponseStatus()).isNull();
        assertThat(record.getResponseBody()).isNull();
        assertThat(record.fingerprintMatches("fp2")).isTrue();
        assertThat(record.isExpired(later)).isFalse();
    }
}
