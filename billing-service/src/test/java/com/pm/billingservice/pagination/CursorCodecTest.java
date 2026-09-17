package com.pm.billingservice.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CursorCodec")
class CursorCodecTest {

    @Test
    @DisplayName("round-trips a cursor losslessly, including nanosecond precision")
    void roundTrips() {
        Cursor original = new Cursor(
                Instant.parse("2026-08-31T10:15:30.123456789Z"),
                UUID.fromString("11111111-2222-3333-4444-555555555555"));

        Cursor decoded = CursorCodec.decode(CursorCodec.encode(original));

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    @DisplayName("produces a URL-safe, unpadded token")
    void tokenIsUrlSafe() {
        String token = CursorCodec.encode(new Cursor(Instant.now(), UUID.randomUUID()));

        assertThat(token).doesNotContain("+", "/", "=");
    }

    @Test
    @DisplayName("rejects a malformed token")
    void rejectsMalformed() {
        assertThatThrownBy(() -> CursorCodec.decode("not-a-valid-cursor!!"))
                .isInstanceOf(IllegalArgumentException.class);
        // Valid base64 but wrong shape (no separator) is still rejected.
        assertThatThrownBy(() -> CursorCodec.decode(
                java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString("no-separator-here".getBytes())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
