package com.pm.billingservice.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pm.billing.grpc.CloseAccountRequest;
import com.pm.billing.grpc.CloseAccountResponse;
import com.pm.billingservice.dto.BillingAccountResponseDTO;
import com.pm.billingservice.exception.AccountHasBalanceException;
import com.pm.billingservice.exception.BillingAccountNotFoundException;
import com.pm.billingservice.model.AccountStatus;
import com.pm.billingservice.service.BillingAccountService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for the deletion-veto RPC: maps deactivation outcomes to gRPC responses/status. */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillingGrpcService.closeAccountForPatient")
class BillingGrpcServiceTest {

    private static final UUID PATIENT_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

    @Mock private BillingAccountService accountService;
    @Mock private Validator validator;
    @Mock private StreamObserver<CloseAccountResponse> responseObserver;
    @InjectMocks private BillingGrpcService grpcService;

    private CloseAccountRequest request() {
        return CloseAccountRequest.newBuilder().setPatientId(PATIENT_ID.toString()).build();
    }

    private BillingAccountResponseDTO dto() {
        return new BillingAccountResponseDTO(
                UUID.randomUUID(), PATIENT_ID, AccountStatus.CLOSED, BigDecimal.ZERO, "USD", 0L);
    }

    @Test
    @DisplayName("empty account → CLOSED")
    void emptyAccountClosed() {
        when(accountService.deactivateForPatient(PATIENT_ID)).thenReturn(dto());

        grpcService.closeAccountForPatient(request(), responseObserver);

        ArgumentCaptor<CloseAccountResponse> captor = ArgumentCaptor.forClass(CloseAccountResponse.class);
        verify(responseObserver).onNext(captor.capture());
        verify(responseObserver).onCompleted();
        assertThat(captor.getValue().getStatus()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("no account → NO_ACCOUNT (nothing to close)")
    void noAccount() {
        when(accountService.deactivateForPatient(PATIENT_ID))
                .thenThrow(new BillingAccountNotFoundException(PATIENT_ID));

        grpcService.closeAccountForPatient(request(), responseObserver);

        ArgumentCaptor<CloseAccountResponse> captor = ArgumentCaptor.forClass(CloseAccountResponse.class);
        verify(responseObserver).onNext(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("NO_ACCOUNT");
    }

    @Test
    @DisplayName("funded account → FAILED_PRECONDITION (vetoes the deletion)")
    void fundedAccountVetoes() {
        when(accountService.deactivateForPatient(PATIENT_ID))
                .thenThrow(new AccountHasBalanceException(UUID.randomUUID(), new BigDecimal("10.00")));

        grpcService.closeAccountForPatient(request(), responseObserver);

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(responseObserver).onError(error.capture());
        assertThat(Status.fromThrowable(error.getValue()).getCode())
                .isEqualTo(Status.Code.FAILED_PRECONDITION);
    }
}
