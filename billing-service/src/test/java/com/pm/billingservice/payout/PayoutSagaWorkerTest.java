package com.pm.billingservice.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import com.pm.billingservice.model.BillingAccount;
import com.pm.billingservice.model.Payout;
import com.pm.billingservice.model.PayoutStatus;
import com.pm.billingservice.repository.BillingAccountRepository;
import com.pm.billingservice.repository.LedgerEntryRepository;
import com.pm.billingservice.repository.PayoutRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("PayoutSagaWorker")
class PayoutSagaWorkerTest {

    @Mock private PayoutRepository payoutRepository;
    @Mock private ExternalSettlementGateway gateway;
    @Mock private BillingAccountRepository accountRepository;
    @Mock private LedgerEntryRepository ledgerRepository;

    private PayoutSagaWorker worker() {
        return new PayoutSagaWorker(payoutRepository, gateway, accountRepository, ledgerRepository);
    }

    private Payout pending() {
        Payout payout = Payout.initiate(
                UUID.randomUUID(), "DE89", new BigDecimal("30.00"), "USD", "k1", "rent");
        ReflectionTestUtils.setField(payout, "id", UUID.randomUUID());
        return payout;
    }

    private void claim(Payout payout) {
        when(payoutRepository.claimDuePending(any(), anyInt())).thenReturn(List.of(payout));
    }

    private void settlementReturns(SettlementOutcome outcome) {
        when(gateway.settle(any(), any(), any(), any())).thenReturn(outcome);
    }

    @Test
    @DisplayName("a settled payout becomes COMPLETED")
    void settledCompletes() {
        Payout payout = pending();
        claim(payout);
        settlementReturns(SettlementOutcome.SETTLED);

        worker().drivePendingPayouts();

        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.COMPLETED);
    }

    @Test
    @DisplayName("a transient error stays PENDING, bumps attempts, and schedules a retry")
    void transientErrorRetries() {
        Payout payout = pending();
        claim(payout);
        settlementReturns(SettlementOutcome.TRANSIENT_ERROR);

        worker().drivePendingPayouts();

        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PENDING);
        assertThat(payout.getAttempts()).isEqualTo(1);
        assertThat(payout.getFailureReason()).isNotBlank();
    }

    @Test
    @DisplayName("a permanent decline is compensated to REVERSED, crediting the debit back")
    void declinedReverses() {
        Payout payout = pending();
        claim(payout);
        settlementReturns(SettlementOutcome.DECLINED);
        BillingAccount source = BillingAccount.openFor(UUID.randomUUID(), "USD");
        when(accountRepository.findByIdForUpdate(payout.getSourceAccountId())).thenReturn(Optional.of(source));

        worker().drivePendingPayouts();

        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.REVERSED);
        assertThat(source.getBalance()).isEqualByComparingTo("30.00"); // debit credited back
    }

    @Test
    @DisplayName("a transient error on the last allowed attempt is parked as FAILED")
    void exhaustedRetriesFail() {
        Payout payout = pending();
        ReflectionTestUtils.setField(payout, "attempts", 4); // one below MAX_ATTEMPTS (5)
        claim(payout);
        settlementReturns(SettlementOutcome.TRANSIENT_ERROR);

        worker().drivePendingPayouts();

        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.FAILED);
    }
}
