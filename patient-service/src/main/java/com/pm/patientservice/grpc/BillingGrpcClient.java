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

    @GrpcClient("billing")
    private BillingServiceGrpc.BillingServiceBlockingStub billingStub;

    /** Closes the patient's account, or throws to veto the deletion. */
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
}
