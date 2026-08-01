package com.pm.patientservice.grpc;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.pm.billing.grpc.BillingServiceGrpc;
import com.pm.billing.grpc.OpenAccountRequest;
import com.pm.billing.grpc.OpenAccountResponse;
import com.pm.patientservice.event.PatientRegisteredEvent;

import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;

/**
 * gRPC client for billing-service. On patient registration it opens the patient's billing account
 * via billing's {@code OpenAccount} RPC. Design choices:
 *
 * <ul>
 *   <li><b>After-commit</b> ({@code @TransactionalEventListener}): the call runs once the patient
 *       transaction has committed, so we never do network I/O while holding a DB transaction.
 *   <li><b>Deadline</b> on every call: a slow/hung billing can never block registration.
 *   <li><b>Best-effort</b>: a billing failure is logged (with the correlation id in MDC) but does
 *       not fail registration — reliable delivery is the Outbox/Saga evolution (see ROADMAP).
 * </ul>
 */
@Component
public class BillingGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(BillingGrpcClient.class);
    private static final long DEADLINE_SECONDS = 3;

    @GrpcClient("billing")
    private BillingServiceGrpc.BillingServiceBlockingStub billingStub;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPatientRegistered(PatientRegisteredEvent event) {
        try {
            OpenAccountResponse response = billingStub
                    .withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS)
                    .openAccount(OpenAccountRequest.newBuilder()
                            .setPatientId(event.patientId().toString())
                            .setCurrency(event.currency())
                            .build());
            log.info("Opened billing account {} for patient {}", response.getAccountId(), event.patientId());
        } catch (StatusRuntimeException e) {
            log.warn(
                    "Could not open billing account for patient {} (gRPC {}): {}",
                    event.patientId(),
                    e.getStatus().getCode(),
                    e.getStatus().getDescription());
        }
    }
}
