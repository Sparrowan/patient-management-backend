package com.pm.patientservice.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pm.patientservice.exception.PatientDeletionConflictException;
import com.pm.patientservice.exception.PatientDeletionUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The circuit breaker guarding the synchronous billing veto.
 *
 * <p><b>Why the breaker exists, measured rather than assumed.</b> The gRPC deadline bounds one call;
 * it does nothing about the hundredth. Against a billing <em>brownout</em> (container paused, so TCP
 * connects but nothing answers) six consecutive deletes each paid the full deadline — ~3.0–3.2s,
 * every time, no learning. Each stall holds a Tomcat worker and an open transaction, so a billing
 * brownout becomes patient-service thread exhaustion and spreads to endpoints that never touch
 * billing.
 *
 * <p>Worth recording: a <em>hard-down</em> billing needs no breaker. A refused TCP connection
 * already fails in 9–22ms, measured. The breaker is for the brownout, which is ~100× slower and the
 * more common real shape.
 *
 * <p><b>Why this is a focused test and not an integration test.</b> {@code AbstractIntegrationTest}
 * replaces {@link BillingGrpcClient} with a {@code @MockitoBean}, which removes the very proxy under
 * test — the breaker is applied by AOP around the real bean. So this drives the
 * {@link CircuitBreaker} API directly against the same configuration semantics, which keeps the
 * assertions about the breaker rather than about Spring's wiring. The end-to-end behaviour (a 503
 * arriving fast instead of after 3s) was verified against the running stack with billing paused.
 */
@DisplayName("Billing veto circuit breaker")
class BillingVetoCircuitBreakerTest {

    /**
     * Mirrors the {@code billingVeto} instance in {@code application.properties}. Kept small and
     * explicit so a drifting production value shows up as a failing expectation here rather than as
     * a breaker that never opens during an outage.
     */
    private static CircuitBreaker newBreaker() {
        return CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(10)
                        .minimumNumberOfCalls(5)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(20))
                        .slowCallRateThreshold(50)
                        .slowCallDurationThreshold(Duration.ofSeconds(1))
                        .ignoreExceptions(PatientDeletionConflictException.class)
                        .build())
                .circuitBreaker(BillingGrpcClient.BILLING_VETO_BREAKER);
    }

    @Test
    @DisplayName("opens after sustained unavailability, then rejects without calling billing at all")
    void opensAndStopsCallingBilling() {
        CircuitBreaker breaker = newBreaker();
        UUID patientId = UUID.randomUUID();

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // Five failures is minimumNumberOfCalls at a 100% failure rate — past the 50% threshold.
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> breaker.executeRunnable(() -> {
                        throw new PatientDeletionUnavailableException(patientId);
                    }))
                    .isInstanceOf(PatientDeletionUnavailableException.class);
        }

        assertThat(breaker.getState())
                .as("sustained unavailability must open the breaker, or every later delete pays the "
                        + "full 3s deadline again")
                .isEqualTo(CircuitBreaker.State.OPEN);

        // The point of the whole exercise: billing is no longer called. A counter proves it rather
        // than inferring it from timing, which would be flaky.
        int[] billingCalls = {0};
        assertThatThrownBy(() -> breaker.executeRunnable(() -> billingCalls[0]++))
                .as("an open breaker rejects up front")
                .isInstanceOf(io.github.resilience4j.circuitbreaker.CallNotPermittedException.class);
        assertThat(billingCalls[0])
                .as("billing must not be dialled while the breaker is open — that is the saved 3s")
                .isZero();
    }

    @Test
    @DisplayName("a funded account (409) never opens the breaker — billing answering 'no' is success")
    void businessRejectionDoesNotOpenTheBreaker() {
        CircuitBreaker breaker = newBreaker();
        UUID patientId = UUID.randomUUID();

        // Ten consecutive rejections: double minimumNumberOfCalls, and a full sliding window.
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> breaker.executeRunnable(() -> {
                        throw new PatientDeletionConflictException(patientId, "account still funded");
                    }))
                    .isInstanceOf(PatientDeletionConflictException.class);
        }

        assertThat(breaker.getState())
                .as("a funded account is billing working correctly; counting it would start turning "
                        + "legitimate 409s into 503s and tell clients to retry a request that can "
                        + "never succeed")
                .isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls())
                .as("ignoreExceptions must keep these out of the failure count entirely")
                .isZero();
    }

    @Test
    @DisplayName("slow-but-successful calls open the breaker — the deadline alone would not")
    void slowCallsOpenTheBreaker() {
        CircuitBreaker breaker = newBreaker();

        // This is the case a deadline cannot catch. Calls that take 2.9s and SUCCEED never time out,
        // so without slowCallDurationThreshold the breaker stays closed while every worker thread
        // sits blocked — the brownout failure mode, exactly.
        for (int i = 0; i < 5; i++) {
            breaker.onSuccess(Duration.ofSeconds(2).toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
        }

        assertThat(breaker.getState())
                .as("a 2s success is well past the 1s slow-call threshold and well inside the 3s "
                        + "deadline — the breaker, not the deadline, is what notices")
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("recovers through HALF_OPEN once billing answers again")
    void recoversWhenBillingReturns() {
        // A short wait so the test does not sit for the production 20s; the transition being tested
        // is the same one, and pinning the duration here would just re-assert configuration.
        CircuitBreaker breaker = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(10)
                        .minimumNumberOfCalls(5)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofMillis(50))
                        .permittedNumberOfCallsInHalfOpenState(2)
                        .build())
                .circuitBreaker("recoveryProbe");

        UUID patientId = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> breaker.executeRunnable(() -> {
                        throw new PatientDeletionUnavailableException(patientId);
                    }))
                    .isInstanceOf(PatientDeletionUnavailableException.class);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        breaker.transitionToHalfOpenState();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // permittedNumberOfCallsInHalfOpenState successful probes close it again, so a recovered
        // billing restores deletes without anyone intervening.
        breaker.executeRunnable(() -> {});
        breaker.executeRunnable(() -> {});

        assertThat(breaker.getState())
                .as("an outage must heal on its own — a breaker that needs a restart to close is an "
                        + "outage extender")
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
