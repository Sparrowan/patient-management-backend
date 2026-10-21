package com.pm.patientservice.grpc;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.pm.billing.grpc.BillingServiceGrpc;
import com.pm.billing.grpc.CloseAccountRequest;
import com.pm.patientservice.exception.PatientDeletionConflictException;
import com.pm.patientservice.exception.PatientDeletionUnavailableException;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;

/**
 * Synchronous gRPC client for billing's deletion veto (over mTLS). Before a patient is deleted this
 * calls {@code CloseAccountForPatient}: billing closes an empty account, or refuses if it still
 * holds funds. gRPC status → domain exception: {@code FAILED_PRECONDITION} → 409 (funded),
 * {@code UNAVAILABLE}/{@code DEADLINE_EXCEEDED} → 503 (can't verify → fail safe, block the delete).
 */
@Component
public class BillingGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(BillingGrpcClient.class);

    private static final long DEADLINE_SECONDS = 3;

    /** Must match the instance name configured under {@code resilience4j.circuitbreaker}. */
    static final String BILLING_VETO_BREAKER = "billingVeto";

    @GrpcClient("billing")
    private BillingServiceGrpc.BillingServiceBlockingStub billingStub;

    /**
     * Closes the patient's account, or throws to veto the deletion.
     *
     * <p><b>Circuit breaker.</b> The deadline bounds one call; it does nothing about the hundredth.
     * Measured against a billing <em>brownout</em> (the container paused, so TCP connects but
     * nothing answers), six consecutive deletes each paid the full deadline — ~3.0–3.2s, every time,
     * with no learning. Each of those stalls holds a Tomcat worker and an open transaction for three
     * seconds, so a billing brownout turns into a patient-service thread-pool exhaustion: the
     * failure propagates to endpoints that have nothing to do with billing.
     *
     * <p>Note that a <em>hard-down</em> billing needs no breaker — a refused TCP connection already
     * fails in 9–22ms. The breaker exists for the brownout, which is both slower (100×) and the more
     * common real-world shape.
     *
     * <p>The fallback preserves the existing contract exactly: {@link
     * PatientDeletionUnavailableException} → 503, the same answer the timeout produced, just
     * immediately. <b>Failing safe is the whole point</b> — an open breaker must never be read as
     * "billing has no objection", because that would let a funded account's patient be deleted. So
     * the breaker changes the <em>latency</em> of the refusal, never the decision.
     *
     * <p>{@link PatientDeletionConflictException} is excluded from the failure count in
     * configuration: a funded account is billing working <em>correctly</em>, and counting those
     * would open the breaker on legitimate business rejections.
     */
    @CircuitBreaker(name = BILLING_VETO_BREAKER, fallbackMethod = "vetoUnavailable")
    public void closeAccountForPatient(UUID patientId) {
        try {
            billingStub.withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS)
                    .closeAccountForPatient(CloseAccountRequest.newBuilder()
                            .setPatientId(patientId.toString())
                            .build());
        } catch (StatusRuntimeException e) {
            Status.Code code = e.getStatus().getCode();
            // The veto failing is operationally significant (it blocks a delete) — log the gRPC
            // status so the cause is visible, not swallowed behind the mapped 409/503.
            log.warn("billing veto gRPC call failed for patient {} — status={} desc={}",
                    patientId, code, e.getStatus().getDescription());
            if (code == Status.Code.FAILED_PRECONDITION) {
                String reason = e.getStatus().getDescription();
                throw new PatientDeletionConflictException(
                        patientId, reason == null ? "billing rejected the deletion" : reason);
            }
            if (code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED) {
                throw new PatientDeletionUnavailableException(patientId);
            }
            throw e; // unexpected — bubble up to the catch-all handler (500)
        }
    }

    /**
     * Resilience4j fallback: invoked both when the breaker is <b>open</b> (rejected without a call)
     * and when the guarded method <b>threw</b>.
     *
     * <p>That dual role is why this rethrows the domain exceptions instead of blanket-converting
     * everything to 503. A funded account arrives here as {@link PatientDeletionConflictException}
     * and must stay a <b>409</b> — turning billing's legitimate "no" into "try later" would tell the
     * client to retry a request that will never succeed, and would hide the real reason (settle the
     * balance first). Only genuine unavailability becomes a 503.
     *
     * <p>Spring resolves the most specific fallback by throwable type, so a single method taking
     * {@code Throwable} keeps the mapping in one place rather than spread over overloads.
     */
    @SuppressWarnings("unused") // invoked reflectively by Resilience4j
    private void vetoUnavailable(UUID patientId, Throwable cause) {
        if (cause instanceof PatientDeletionConflictException conflict) {
            throw conflict; // billing answered, and the answer was "no" — a 409, not a 503
        }
        if (cause instanceof PatientDeletionUnavailableException unavailable) {
            throw unavailable; // already the right mapping; don't re-wrap
        }
        // Either the breaker is open (CallNotPermittedException — no call was made) or the call
        // failed in a way the try/catch above did not translate. Both mean "cannot verify", and the
        // safe answer to that is to refuse the delete.
        log.warn("billing veto unavailable for patient {} — breaker fallback, cause={}: {}",
                patientId, cause.getClass().getSimpleName(), cause.getMessage());
        throw new PatientDeletionUnavailableException(patientId);
    }
}
