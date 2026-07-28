package com.pm.billingservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pm.billingservice.dto.BillingAccountResponseDTO;
import com.pm.billingservice.dto.OpenAccountRequestDTO;
import com.pm.billingservice.dto.PagedResponse;
import com.pm.billingservice.exception.AccountAlreadyExistsException;
import com.pm.billingservice.exception.BillingAccountNotFoundException;
import com.pm.billingservice.mapper.BillingAccountMapper;
import com.pm.billingservice.model.AccountStatus;
import com.pm.billingservice.model.BillingAccount;
import com.pm.billingservice.repository.BillingAccountRepository;
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

/** Unit tests for the service layer in isolation — repository and mapper are mocked. */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillingAccountServiceImpl")
class BillingAccountServiceImplTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PATIENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private BillingAccountRepository accountRepository;
    @Mock private BillingAccountMapper accountMapper;
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

    @Nested
    @DisplayName("openAccount")
    class OpenAccount {

        private final OpenAccountRequestDTO request = new OpenAccountRequestDTO(PATIENT_ID, "USD");

        @Test
        @DisplayName("opens an active, zero-balance account for the patient")
        void opensAccount() {
            when(accountRepository.existsByPatientId(PATIENT_ID)).thenReturn(false);
            when(accountRepository.save(any(BillingAccount.class))).thenAnswer(inv -> inv.getArgument(0));
            when(accountMapper.toResponse(any(BillingAccount.class))).thenReturn(response());

            BillingAccountResponseDTO result = service.openAccount(request);

            assertThat(result).isEqualTo(response());
            ArgumentCaptor<BillingAccount> saved = ArgumentCaptor.forClass(BillingAccount.class);
            verify(accountRepository).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(saved.getValue().getBalance()).isEqualByComparingTo("0.00");
            assertThat(saved.getValue().getPatientId()).isEqualTo(PATIENT_ID);
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
    @DisplayName("getAccount")
    class GetAccount {

        @Test
        @DisplayName("returns the account when it exists")
        void returnsAccount() {
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
            when(accountMapper.toResponse(any(BillingAccount.class))).thenReturn(response());

            assertThat(service.getAccount(ACCOUNT_ID)).isEqualTo(response());
        }

        @Test
        @DisplayName("throws when absent")
        void throwsWhenMissing() {
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getAccount(ACCOUNT_ID))
                    .isInstanceOf(BillingAccountNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("getAccountByPatient")
    class GetAccountByPatient {

        @Test
        @DisplayName("returns the patient's account")
        void returnsAccount() {
            when(accountRepository.findByPatientId(PATIENT_ID)).thenReturn(Optional.of(account()));
            when(accountMapper.toResponse(any(BillingAccount.class))).thenReturn(response());

            assertThat(service.getAccountByPatient(PATIENT_ID)).isEqualTo(response());
        }

        @Test
        @DisplayName("throws when the patient has no account")
        void throwsWhenMissing() {
            when(accountRepository.findByPatientId(PATIENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getAccountByPatient(PATIENT_ID))
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
            assertThat(result.totalElements()).isEqualTo(1);
        }
    }
}
