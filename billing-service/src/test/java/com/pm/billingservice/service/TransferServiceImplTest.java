package com.pm.billingservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pm.billingservice.dto.TransferRequestDTO;
import com.pm.billingservice.dto.TransferResponseDTO;
import com.pm.billingservice.exception.CurrencyMismatchException;
import com.pm.billingservice.mapper.TransferMapper;
import com.pm.billingservice.model.BillingAccount;
import com.pm.billingservice.model.LedgerEntry;
import com.pm.billingservice.model.Transfer;
import com.pm.billingservice.model.TransferStatus;
import com.pm.billingservice.repository.BillingAccountRepository;
import com.pm.billingservice.repository.LedgerEntryRepository;
import com.pm.billingservice.repository.TransferRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransferServiceImpl")
class TransferServiceImplTest {

    private static final UUID LOW = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID HIGH = UUID.fromString("99999999-9999-9999-9999-999999999999");

    @Mock private BillingAccountRepository accountRepository;
    @Mock private LedgerEntryRepository ledgerRepository;
    @Mock private TransferRepository transferRepository;
    @Mock private TransferMapper transferMapper;

    private TransferServiceImpl service() {
        return new TransferServiceImpl(accountRepository, ledgerRepository, transferRepository, transferMapper);
    }

    private BillingAccount account(UUID id, String currency, String balance) {
        BillingAccount account = BillingAccount.openFor(UUID.randomUUID(), currency);
        ReflectionTestUtils.setField(account, "id", id);
        if (new BigDecimal(balance).signum() > 0) {
            account.credit(new BigDecimal(balance));
        }
        return account;
    }

    private void stubSaves() {
        when(transferRepository.save(any(Transfer.class))).thenAnswer(invocation -> {
            Transfer transfer = invocation.getArgument(0);
            ReflectionTestUtils.setField(transfer, "id", UUID.randomUUID());
            return transfer;
        });
        when(ledgerRepository.save(any(LedgerEntry.class))).thenAnswer(i -> i.getArgument(0));
        when(transferMapper.toResponse(any(Transfer.class))).thenReturn(sampleResponse());
    }

    private TransferResponseDTO sampleResponse() {
        return new TransferResponseDTO(UUID.randomUUID(), HIGH, LOW, new BigDecimal("30.00"),
                "USD", TransferStatus.COMPLETED, null, "k1", null);
    }

    @Test
    @DisplayName("moves money, saves the transfer, and writes two double-entry legs")
    void happyPath() {
        BillingAccount source = account(HIGH, "USD", "100.00");
        BillingAccount destination = account(LOW, "USD", "0.00");
        when(transferRepository.findByIdempotencyKey("k1")).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(HIGH)).thenReturn(Optional.of(source));
        when(accountRepository.findByIdForUpdate(LOW)).thenReturn(Optional.of(destination));
        stubSaves();

        service().transfer(new TransferRequestDTO(HIGH, LOW, new BigDecimal("30.00"), null), "k1");

        assertThat(source.getBalance()).isEqualByComparingTo("70.00");
        assertThat(destination.getBalance()).isEqualByComparingTo("30.00");
        verify(transferRepository).save(any(Transfer.class));
        verify(ledgerRepository, times(2)).save(any(LedgerEntry.class)); // debit leg + credit leg
    }

    @Test
    @DisplayName("locks the two accounts in a fixed id order regardless of transfer direction (deadlock-safe)")
    void locksInIdOrder() {
        BillingAccount source = account(HIGH, "USD", "100.00");
        BillingAccount destination = account(LOW, "USD", "0.00");
        when(transferRepository.findByIdempotencyKey("k1")).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(HIGH)).thenReturn(Optional.of(source));
        when(accountRepository.findByIdForUpdate(LOW)).thenReturn(Optional.of(destination));
        stubSaves();

        // Transfer HIGH -> LOW. Locks must be acquired in a *fixed* order (whatever UUID.compareTo says,
        // which is signed) — the same order a LOW -> HIGH transfer would use — so opposing transfers
        // can't deadlock. Derive the expected order from compareTo rather than assuming lexicographic.
        service().transfer(new TransferRequestDTO(HIGH, LOW, new BigDecimal("30.00"), null), "k1");

        UUID firstLocked = HIGH.compareTo(LOW) <= 0 ? HIGH : LOW;
        UUID secondLocked = firstLocked.equals(HIGH) ? LOW : HIGH;
        InOrder inOrder = inOrder(accountRepository);
        inOrder.verify(accountRepository).findByIdForUpdate(firstLocked);
        inOrder.verify(accountRepository).findByIdForUpdate(secondLocked);
    }

    @Test
    @DisplayName("a retried transfer replays the original and never touches the accounts")
    void idempotentReplay() {
        Transfer existing = Transfer.record(HIGH, LOW, new BigDecimal("30.00"), "USD", "k1", null);
        when(transferRepository.findByIdempotencyKey("k1")).thenReturn(Optional.of(existing));
        when(transferMapper.toResponse(existing)).thenReturn(sampleResponse());

        service().transfer(new TransferRequestDTO(HIGH, LOW, new BigDecimal("30.00"), null), "k1");

        verify(accountRepository, never()).findByIdForUpdate(any());
        verifyNoInteractions(ledgerRepository);
        verify(transferRepository, never()).save(any());
    }

    @Test
    @DisplayName("a cross-currency transfer is rejected")
    void currencyMismatch() {
        BillingAccount source = account(HIGH, "USD", "100.00");
        BillingAccount destination = account(LOW, "EUR", "0.00");
        when(transferRepository.findByIdempotencyKey("k1")).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(HIGH)).thenReturn(Optional.of(source));
        when(accountRepository.findByIdForUpdate(LOW)).thenReturn(Optional.of(destination));

        assertThatThrownBy(() ->
                service().transfer(new TransferRequestDTO(HIGH, LOW, new BigDecimal("30.00"), null), "k1"))
                .isInstanceOf(CurrencyMismatchException.class);

        verify(transferRepository, never()).save(any());
        verifyNoInteractions(ledgerRepository);
    }
}
