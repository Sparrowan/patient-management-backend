package com.pm.billingservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pm.billingservice.dto.BillingAccountResponseDTO;
import com.pm.billingservice.dto.LedgerEntryResponseDTO;
import com.pm.billingservice.dto.MoneyMovementRequestDTO;
import com.pm.billingservice.dto.OpenAccountRequestDTO;
import com.pm.billingservice.dto.PagedResponse;
import com.pm.billingservice.exception.AccountAlreadyExistsException;
import com.pm.billingservice.exception.BillingAccountNotFoundException;
import com.pm.billingservice.exception.InsufficientFundsException;
import com.pm.billingservice.mapper.BillingAccountMapper;
import com.pm.billingservice.mapper.LedgerEntryMapper;
import com.pm.billingservice.model.AccountStatus;
import com.pm.billingservice.model.BillingAccount;
import com.pm.billingservice.model.EntryType;
import com.pm.billingservice.model.LedgerEntry;
import com.pm.billingservice.repository.BillingAccountRepository;
import com.pm.billingservice.repository.LedgerEntryRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/** Unit tests for the service layer in isolation — repositories and mappers are mocked. */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillingAccountServiceImpl")
class BillingAccountServiceImplTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PATIENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ENTRY_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock private BillingAccountRepository accountRepository;
    @Mock private LedgerEntryRepository ledgerRepository;
    @Mock private BillingAccountMapper accountMapper;
    @Mock private LedgerEntryMapper ledgerMapper;
    @InjectMocks private BillingAccountServiceImpl service;

    private BillingAccount account() {
        BillingAccount account = BillingAccount.openFor(PATIENT_ID, "USD");
        ReflectionTestUtils.setField(account, "id", ACCOUNT_ID);
        return account;
    }

    private BillingAccountResponseDTO response() {
        return new BillingAccountResponseDTO(
                ACCOUNT_ID, PATIENT_ID, AccountStatus.ACTIVE, new BigDecimal("0.00"), "USD", 0L);
    }

    private LedgerEntry ledgerEntry(String key) {
        LedgerEntry entry = LedgerEntry.record(
                ACCOUNT_ID, EntryType.CREDIT, new BigDecimal("50.00"), new BigDecimal("50.00"), key, "topup");
        ReflectionTestUtils.setField(entry, "id", ENTRY_ID);
        return entry;
    }

    private LedgerEntryResponseDTO ledgerResponse() {
        return new LedgerEntryResponseDTO(
                ENTRY_ID, ACCOUNT_ID, EntryType.CREDIT, new BigDecimal("50.00"), new BigDecimal("50.00"),
                "topup", "k1", null);
    }

    @Nested
    @DisplayName("openAccount")
    class OpenAccount {

        private final OpenAccountRequestDTO request = new OpenAccountRequestDTO(PATIENT_ID, "USD");

        @Test
        @DisplayName("opens an active, zero-balance account")
        void opensAccount() {
            when(accountRepository.existsByPatientId(PATIENT_ID)).thenReturn(false);
            when(accountRepository.save(any(BillingAccount.class))).thenAnswer(inv -> inv.getArgument(0));
            when(accountMapper.toResponse(any(BillingAccount.class))).thenReturn(response());

            assertThat(service.openAccount(request)).isEqualTo(response());
            ArgumentCaptor<BillingAccount> saved = ArgumentCaptor.forClass(BillingAccount.class);
            verify(accountRepository).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(saved.getValue().getBalance()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("rejects a second account for the same patient")
        void rejectsDuplicate() {
            when(accountRepository.existsByPatientId(PATIENT_ID)).thenReturn(true);
            assertThatThrownBy(() -> service.openAccount(request))
                    .isInstanceOf(AccountAlreadyExistsException.class);
            verify(accountRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("getAccount / getAccountByPatient")
    class Reads {

        @Test
        @DisplayName("returns the account by id")
        void byId() {
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
            when(accountMapper.toResponse(any(BillingAccount.class))).thenReturn(response());
            assertThat(service.getAccount(ACCOUNT_ID)).isEqualTo(response());
        }

        @Test
        @DisplayName("throws when id absent")
        void byIdMissing() {
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.getAccount(ACCOUNT_ID))
                    .isInstanceOf(BillingAccountNotFoundException.class);
        }

        @Test
        @DisplayName("returns the account by patient")
        void byPatient() {
            when(accountRepository.findByPatientId(PATIENT_ID)).thenReturn(Optional.of(account()));
            when(accountMapper.toResponse(any(BillingAccount.class))).thenReturn(response());
            assertThat(service.getAccountByPatient(PATIENT_ID)).isEqualTo(response());
        }
    }

    @Nested
    @DisplayName("credit / debit")
    class MoneyMovement {

        @Test
        @DisplayName("credit applies to the balance and records a ledger entry")
        void creditApplies() {
            BillingAccount account = account();
            when(ledgerRepository.findByIdempotencyKey("k1")).thenReturn(Optional.empty());
            when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
            when(ledgerRepository.save(any(LedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));
            when(ledgerMapper.toResponse(any(LedgerEntry.class))).thenReturn(ledgerResponse());

            service.credit(ACCOUNT_ID, new MoneyMovementRequestDTO(new BigDecimal("50.00"), "topup"), "k1");

            assertThat(account.getBalance()).isEqualByComparingTo("50.00");
            verify(accountRepository).save(account);
            verify(ledgerRepository).save(any(LedgerEntry.class));
        }

        @Test
        @DisplayName("credit is idempotent: a seen key replays without re-applying")
        void creditIsIdempotent() {
            when(ledgerRepository.findByIdempotencyKey("k1")).thenReturn(Optional.of(ledgerEntry("k1")));
            when(ledgerMapper.toResponse(any(LedgerEntry.class))).thenReturn(ledgerResponse());

            LedgerEntryResponseDTO result =
                    service.credit(ACCOUNT_ID, new MoneyMovementRequestDTO(new BigDecimal("50.00"), null), "k1");

            assertThat(result).isEqualTo(ledgerResponse());
            verify(accountRepository, never()).findByIdForUpdate(any());
            verify(accountRepository, never()).save(any());
            verify(ledgerRepository, never()).save(any());
        }

        @Test
        @DisplayName("debit applies when funds are sufficient")
        void debitApplies() {
            BillingAccount account = account();
            account.credit(new BigDecimal("100.00"));
            when(ledgerRepository.findByIdempotencyKey("k2")).thenReturn(Optional.empty());
            when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
            when(ledgerRepository.save(any(LedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));
            when(ledgerMapper.toResponse(any(LedgerEntry.class))).thenReturn(ledgerResponse());

            service.debit(ACCOUNT_ID, new MoneyMovementRequestDTO(new BigDecimal("30.00"), null), "k2");

            assertThat(account.getBalance()).isEqualByComparingTo("70.00");
        }

        @Test
        @DisplayName("debit fails with insufficient funds and records nothing")
        void debitInsufficient() {
            BillingAccount account = account(); // balance 0.00
            when(ledgerRepository.findByIdempotencyKey("k3")).thenReturn(Optional.empty());
            when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));

            assertThatThrownBy(() -> service.debit(
                            ACCOUNT_ID, new MoneyMovementRequestDTO(new BigDecimal("10.00"), null), "k3"))
                    .isInstanceOf(InsufficientFundsException.class);
            verify(ledgerRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("getLedger")
    class GetLedger {

        @Test
        @DisplayName("returns a page of entries for an existing account")
        void returnsPage() {
            LedgerEntry entry = ledgerEntry("k1");
            when(accountRepository.existsById(ACCOUNT_ID)).thenReturn(true);
            when(ledgerRepository.findByAccountId(any(UUID.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(entry), PageRequest.of(0, 20), 1));
            when(ledgerMapper.toResponse(entry)).thenReturn(ledgerResponse());

            PagedResponse<LedgerEntryResponseDTO> result = service.getLedger(ACCOUNT_ID, PageRequest.of(0, 20));

            assertThat(result.content()).containsExactly(ledgerResponse());
        }

        @Test
        @DisplayName("throws when the account does not exist")
        void throwsWhenMissing() {
            when(accountRepository.existsById(ACCOUNT_ID)).thenReturn(false);
            assertThatThrownBy(() -> service.getLedger(ACCOUNT_ID, PageRequest.of(0, 20)))
                    .isInstanceOf(BillingAccountNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("getAccounts")
    class GetAccounts {

        @Test
        @DisplayName("maps the page and reports totals")
        void returnsPagedResponse() {
            BillingAccount account = account();
            Page<BillingAccount> page = new PageImpl<>(List.of(account), PageRequest.of(0, 20), 1);
            when(accountRepository.findAll(any(Pageable.class))).thenReturn(page);
            when(accountMapper.toResponse(account)).thenReturn(response());

            PagedResponse<BillingAccountResponseDTO> result = service.getAccounts(PageRequest.of(0, 20));
            assertThat(result.content()).containsExactly(response());
        }
    }
}
