package com.pm.billingservice.pagination;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Encodes a {@link Cursor} to (and from) an opaque, URL-safe token.
 *
 * <p><b>Why opaque?</b> The token is part of the API contract, but its <em>internals</em> are not —
 * clients must treat it as a black box they echo back, never something they parse or construct. That
 * frees us to change the sort key later (add a field, change the tiebreak) without breaking clients.
 * Base64-url of {@code "<createdAt ISO-8601>|<uuid>"} — {@link Instant#toString()} /
 * {@link Instant#parse} round-trips nanosecond precision exactly, so the position is lossless.
 *
 * <p>Not a security boundary — it's not signed or encrypted; it only encodes a position that the
 * WHERE clause would expose anyway. A tampered token can't leak another account's rows (the query is
 * still scoped by {@code account_id}); at worst it's rejected as malformed.
 */
public final class CursorCodec {

    private static final char SEPARATOR = '|';

    private CursorCodec() {
    }

    public static String encode(Cursor cursor) {
        String raw = cursor.createdAt().toString() + SEPARATOR + cursor.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes an opaque token back to a {@link Cursor}.
     *
     * @throws IllegalArgumentException if the token is not a cursor this service issued (bad base64,
     *     missing separator, unparseable timestamp/uuid) — the caller maps this to 400.
     */
    public static Cursor decode(String token) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int separator = raw.indexOf(SEPARATOR);
            if (separator < 0) {
                throw new IllegalArgumentException("cursor missing separator");
            }
            Instant createdAt = Instant.parse(raw.substring(0, separator));
            UUID id = UUID.fromString(raw.substring(separator + 1));
            return new Cursor(createdAt, id);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Malformed pagination cursor", e);
        }
    }
}
