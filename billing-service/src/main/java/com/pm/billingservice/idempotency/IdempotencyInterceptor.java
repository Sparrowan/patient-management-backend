package com.pm.billingservice.idempotency;

import com.pm.billingservice.exception.IdempotencyConflictException;
import com.pm.billingservice.exception.IdempotencyKeyMissingException;
import com.pm.billingservice.exception.IdempotencyKeyReuseException;
import com.pm.billingservice.model.IdempotencyRecord;
import com.pm.billingservice.repository.IdempotencyRecordRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.WebUtils;

/**
 * The generic HTTP idempotency mechanism. Around any handler marked {@link Idempotent}, it runs:
 *
 * <ol>
 *   <li><b>Claim / replay</b> ({@link #preHandle}): look the {@code (user, Idempotency-Key)} up in the
 *       store. A completed, unexpired record → write the stored response and short-circuit (the
 *       handler never runs). No record → insert an {@code IN_PROGRESS} claim and proceed.</li>
 *   <li><b>Capture</b> ({@link #afterCompletion}): read the response the handler produced off the
 *       caching wrapper and flip the claim to {@code COMPLETED} with that response stored. If the
 *       request failed (threw), delete the claim so the key is free for a clean retry.</li>
 * </ol>
 *
 * <p><b>The honest seam:</b> the claim commits in its own transaction, the business change commits in
 * the controller's, and the capture commits in a third — so a crash between the business commit and
 * the capture leaves an {@code IN_PROGRESS} row. That's why this is a convenience layer on top of the
 * domain unique-key idempotency (ledger / transfer / payout), which remains the correctness backstop
 * for money: a re-run after such a crash still can't double-apply. (In-flight 409, fingerprint 422,
 * error-caching policy and a lease timeout for stuck claims are layered on next.)
 */
@Component
@RequiredArgsConstructor
public class IdempotencyInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyInterceptor.class);

    static final String HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";
    /** How long a completed response stays replayable; after this a key may be reused (Stripe's 24h). */
    private static final Duration TTL = Duration.ofHours(24);
    /** How long an IN_PROGRESS claim is assumed live; past it the original is presumed dead and a retry takes over. */
    private static final Duration CLAIM_LEASE = Duration.ofSeconds(60);
    private static final String CAPTURE_ATTR = IdempotencyInterceptor.class.getName() + ".capture";
    private static final String METRIC = "billing.idempotency.requests";

    private final IdempotencyRecordRepository repository;
    private final MeterRegistry meterRegistry;

    /** Counts an idempotency decision so replays/conflicts/reuse are visible in Prometheus. */
    private void count(String outcome) {
        meterRegistry.counter(METRIC, "outcome", outcome).increment();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || method.getMethodAnnotation(Idempotent.class) == null) {
            return true; // not an @Idempotent endpoint — no-op
        }
        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank()) {
            count("missing_key");
            throw new IdempotencyKeyMissingException(); // 400 — the layer enforces the header itself
        }
        String userSub = currentUserSub();
        if (userSub == null) {
            return true; // unauthenticated: let the security chain answer (shouldn't reach a secured handler)
        }

        String fingerprint = fingerprint(request);
        Instant now = Instant.now();
        Optional<IdempotencyRecord> existing = repository.findByUserSubAndIdKey(userSub, key);

        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            boolean live = !record.isExpired(now);
            // A key that's still within its TTL must mean the same request; a different body is a client bug.
            if (live && !record.fingerprintMatches(fingerprint)) {
                count("reuse");
                throw new IdempotencyKeyReuseException(); // 422
            }
            if (record.isCompleted()) {
                if (live) {
                    count("replayed");
                    replay(response, record);
                    return false; // short-circuit — the original response is authoritative
                }
                // TTL lapsed → the key is recycled: re-open for a fresh attempt.
                return reopenAndClaim(request, record, fingerprint, now, userSub, key);
            }
            // IN_PROGRESS:
            if (live && !record.isClaimStale(now, CLAIM_LEASE)) {
                count("conflict");
                throw new IdempotencyConflictException(); // 409 — a duplicate is genuinely in flight
            }
            // Stale claim (original died) or expired → take it over.
            count("taken_over");
            return reopenAndClaim(request, record, fingerprint, now, userSub, key);
        }

        try {
            repository.saveAndFlush(IdempotencyRecord.start(
                    key, userSub, request.getMethod(), request.getRequestURI(), fingerprint, now, TTL));
            request.setAttribute(CAPTURE_ATTR, new CaptureContext(userSub, key));
            count("new");
        } catch (DataIntegrityViolationException raceLost) {
            // A concurrent request won the unique-constraint claim between our lookup and insert — so a
            // duplicate is in flight right now: 409, same as the live IN_PROGRESS case above.
            count("conflict");
            throw new IdempotencyConflictException();
        }
        return true;
    }

    private boolean reopenAndClaim(
            HttpServletRequest request, IdempotencyRecord record, String fingerprint,
            Instant now, String userSub, String key) {
        record.reopen(fingerprint, now, TTL);
        repository.saveAndFlush(record);
        request.setAttribute(CAPTURE_ATTR, new CaptureContext(userSub, key));
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Object attr = request.getAttribute(CAPTURE_ATTR);
        if (!(attr instanceof CaptureContext context)) {
            return; // we didn't claim this request (replay, non-idempotent, or a conflict)
        }
        Optional<IdempotencyRecord> maybe = repository.findByUserSubAndIdKey(context.userSub(), context.key());
        if (maybe.isEmpty()) {
            return;
        }
        IdempotencyRecord record = maybe.get();
        if (record.isCompleted()) {
            return; // already captured elsewhere
        }
        int status = response.getStatus();
        // Only cache a FINAL response. A 5xx (or an unresolved throw) is transient — delete the claim so
        // the key is free for a clean retry; caching it would pin a client to a one-off server error.
        // 2xx and deterministic 4xx (validation, insufficient funds, ...) are stable → cache and replay.
        if (ex != null || status >= 500) {
            repository.delete(record);
            return;
        }
        ContentCachingResponseWrapper wrapper =
                WebUtils.getNativeResponse(response, ContentCachingResponseWrapper.class);
        String body = wrapper != null
                ? new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8)
                : "";
        record.complete(status, body, response.getContentType());
        repository.save(record);
    }

    /** Writes the stored response back to the client verbatim (status, content type, body). */
    private void replay(HttpServletResponse response, IdempotencyRecord record) {
        try {
            response.setStatus(record.getResponseStatus());
            response.setHeader(REPLAYED_HEADER, "true"); // let clients/observability see a replay
            if (record.getResponseContentType() != null) {
                response.setContentType(record.getResponseContentType());
            }
            if (record.getResponseBody() != null) {
                response.getOutputStream().write(record.getResponseBody().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            log.warn("Failed to replay idempotent response: {}", e.toString());
        }
    }

    /** SHA-256 hex of method + path + body — a reuse of the key with a different request is detectable. */
    private String fingerprint(HttpServletRequest request) {
        CachedBodyHttpServletRequest cached =
                WebUtils.getNativeRequest(request, CachedBodyHttpServletRequest.class);
        byte[] body = cached != null ? cached.getCachedBody() : new byte[0];
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(request.getMethod().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(request.getRequestURI().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // never on a standard JRE
        }
    }

    private String currentUserSub() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return null;
        }
        return auth.getName();
    }

    /** What {@link #preHandle} hands {@link #afterCompletion} to identify the row it claimed. */
    private record CaptureContext(String userSub, String key) {
    }
}
