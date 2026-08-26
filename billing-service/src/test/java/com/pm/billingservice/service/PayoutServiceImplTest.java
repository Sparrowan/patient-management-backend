package com.pm.billingservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pm.billingservice.dto.PayoutRequestDTO;
import com.pm.billingservice.dto.PayoutResponseDTO;
import com.pm.billingservice.exception.InsufficientFundsException;
import com.pm.billingservice.mapper.PayoutMapper;
import com.pm.billingservice.model.BillingAccount;
import com.pm.billingservice.model.LedgerEntry;
import com.pm.billingservice.model.Payout;
import com.pm.billingservice.model.PayoutStatus;
import com.pm.billingservice.repository.BillingAccountRepository;
import com.pm.billingservice.repository.LedgerEntryRepository;
import com.pm.billingservice.repository.PayoutRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("PayoutServiceImpl")
class PayoutServiceImplTest {

    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String DEST = "DE89370400440532013000";

    @Mock private BillingAccountRepository accountRepository;
    @Mock private LedgerEntryRepository ledgerRepository;
    @Mock private PayoutRepository payoutRepository;
    @Mock private PayoutMapper payoutMapper;

    private PayoutServiceImpl service() {
        return new PayoutServiceImpl(accountRepository, ledgerRepository, payoutRepository, payoutMapper);
    }

    private BillingAccount account(String currency, String balance) {
        BillingAccount account = BillingAccount.openFor(UUID.randomUUID(), currency);
        ReflectionTestUtils.setField(account, "id", ACCOUNT);
        if (new BigDecimal(balance).signum() > 0) {
            account.credit(new BigDecimal(balance));
        }
        return account;
    }

    private PayoutRequestDTO request(String amount) {
        return new PayoutRequestDTO(ACCOUNT, DEST, new BigDecimal(amount), "rent");
    }

    private PayoutResponseDTO sampleResponse() {
        return new PayoutResponseDTO(UUID.randomUUID(), ACCOUNT, DEST, new BigDecimal("30.00"),
                "USD", PayoutStatus.PENDING, "rent", "k1", null);
    }

    @Test
    @DisplayName("debits the source, records a PENDING payout, and writes a single DEBIT ledger leg")
    void happyPath() {
        BillingAccount source = account("USD", "100.00");
        when(payoutRepository.findByIdempotencyKey("k1")).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(ACCOUNT)).thenReturn(Optional.of(source));
        when(payoutRepository.save(any(Payout.class))).thenAnswer(invocation -> {
            Payout p = invocation.getArgument(0);
            ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
            return p;
        });
        when(ledgerRepository.save(any(LedgerEntry.class))).thenAnswer(i -> i.getArgument(0));
        when(payoutMapper.toResponse(any(Payout.class))).thenReturn(sampleResponse());

        service().initiate(request("30.00"), "k1");

        assertThat(source.getBalance()).isEqualByComparingTo("70.00"); // debited up front
        verify(payoutRepository).save(any(Payout.class));
        verify(ledgerRepository).save(any(LedgerEntry.class)); // exactly one leg (the DEBIT)
    }

    @Test
    @DisplayName("a retried initiate replays the original payout and never debits again")
    void idempotentReplay() {
        Payout existing = Payout.initiate(ACCOUNT, DEST, new BigDecimal("30.00"), "USD", "k1", "rent");
        when(payoutRepository.findByIdempotencyKey("k1")).thenReturn(Optional.of(existing));
        when(payoutMapper.toResponse(existing)).thenReturn(sampleResponse());

        service().initiate(request("30.00"), "k1");

        verify(accountRepository, never()).findByIdForUpdate(any());
        verifyNoInteractions(ledgerRepository);
        verify(payoutRepository, never()).save(any());
    }

    @Test
    @DisplayName("paying out more than the account holds is rejected and nothing is written")
    void insufficientFunds() {
        BillingAccount source = account("USD", "10.00");
        when(payoutRepository.findByIdempotencyKey("k1")).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(ACCOUNT)).thenReturn(Optional.of(source));

        assertThatThrownBy(() -> service().initiate(request("50.00"), "k1"))
                .isInstanceOf(InsufficientFundsException.class);

        verify(payoutRepository, never()).save(any());
        verifyNoInteractions(ledgerRepository);
    }
}
