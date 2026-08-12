package com.pm.billingservice.grpc;

import com.pm.billing.grpc.BillingServiceGrpc;
import com.pm.billing.grpc.CloseAccountRequest;
import com.pm.billing.grpc.CloseAccountResponse;
import com.pm.billing.grpc.OpenAccountRequest;
import com.pm.billing.grpc.OpenAccountResponse;
import com.pm.billingservice.dto.BillingAccountResponseDTO;
import com.pm.billingservice.dto.OpenAccountRequestDTO;
import com.pm.billingservice.exception.AccountAlreadyExistsException;
import com.pm.billingservice.exception.AccountHasBalanceException;
import com.pm.billingservice.exception.BillingAccountNotFoundException;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * gRPC entry point for billing. A thin transport adapter: it validates the request, delegates to
 * the existing {@link com.pm.billingservice.service.BillingAccountService} (same logic the REST
 * layer uses), and maps outcomes to gRPC status codes — bad input → {@code INVALID_ARGUMENT},
 * duplicate → {@code ALREADY_EXISTS}. {@code @GrpcService} registers it with the gRPC server.
 */
@GrpcService
@RequiredArgsConstructor
public class BillingGrpcService extends BillingServiceGrpc.BillingServiceImplBase {

    private final com.pm.billingservice.service.BillingAccountService accountService;
    private final Validator validator;

    @Override
    public void openAccount(OpenAccountRequest request, StreamObserver<OpenAccountResponse> responseObserver) {
        UUID patientId;
        try {
            patientId = UUID.fromString(request.getPatientId());
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("patientId must be a UUID")
                    .asRuntimeException());
            return;
        }

        OpenAccountRequestDTO dto = new OpenAccountRequestDTO(patientId, request.getCurrency());
        Set<ConstraintViolation<OpenAccountRequestDTO>> violations = validator.validate(dto);
        if (!violations.isEmpty()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(violations.iterator().next().getMessage())
                    .asRuntimeException());
            return;
        }

        try {
            BillingAccountResponseDTO account = accountService.openAccount(dto);
            responseObserver.onNext(OpenAccountResponse.newBuilder()
                    .setAccountId(account.id().toString())
                    .setStatus(account.status().name())
                    .setBalance(account.balance().toPlainString())
                    .setCurrency(account.currency())
                    .build());
            responseObserver.onCompleted();
        } catch (AccountAlreadyExistsException e) {
            responseObserver.onError(
                    Status.ALREADY_EXISTS.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    /**
     * Synchronous precondition for deleting a patient: close the account, or reject the deletion if
     * it still holds funds. Reuses {@link com.pm.billingservice.service.BillingAccountService#deactivateForPatient}.
     */
    @Override
    public void closeAccountForPatient(
            CloseAccountRequest request, StreamObserver<CloseAccountResponse> responseObserver) {
        UUID patientId;
        try {
            patientId = UUID.fromString(request.getPatientId());
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("patientId must be a UUID")
                    .asRuntimeException());
            return;
        }

        try {
            accountService.deactivateForPatient(patientId);
            responseObserver.onNext(CloseAccountResponse.newBuilder().setStatus("CLOSED").build());
            responseObserver.onCompleted();
        } catch (BillingAccountNotFoundException e) {
            // No account for this patient (e.g. registration not yet processed) — nothing to close.
            responseObserver.onNext(CloseAccountResponse.newBuilder().setStatus("NO_ACCOUNT").build());
            responseObserver.onCompleted();
        } catch (AccountHasBalanceException e) {
            // Funded → the patient may not be deleted until settled. The caller maps this to 409.
            responseObserver.onError(
                    Status.FAILED_PRECONDITION.withDescription(e.getMessage()).asRuntimeException());
        }
    }
}
