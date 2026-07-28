package com.pm.billingservice.service;

import com.pm.billingservice.dto.BillingAccountResponseDTO;
import com.pm.billingservice.dto.OpenAccountRequestDTO;
import com.pm.billingservice.dto.PagedResponse;
import com.pm.billingservice.exception.AccountAlreadyExistsException;
import com.pm.billingservice.exception.BillingAccountNotFoundException;
import com.pm.billingservice.mapper.BillingAccountMapper;
import com.pm.billingservice.model.BillingAccount;
import com.pm.billingservice.repository.BillingAccountRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BillingAccountServiceImpl implements BillingAccountService {

    private final BillingAccountRepository accountRepository;
    private final BillingAccountMapper accountMapper;

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<BillingAccountResponseDTO> getAccounts(Pageable pageable) {
        return PagedResponse.from(accountRepository.findAll(pageable).map(accountMapper::toResponse));
    }

    @Override
    @Transactional(readOnly = true)
    public BillingAccountResponseDTO getAccount(UUID id) {
        return accountMapper.toResponse(
                accountRepository.findById(id).orElseThrow(() -> new BillingAccountNotFoundException(id)));
    }

    @Override
    @Transactional(readOnly = true)
    public BillingAccountResponseDTO getAccountByPatient(UUID patientId) {
        return accountMapper.toResponse(accountRepository
                .findByPatientId(patientId)
                .orElseThrow(() -> new BillingAccountNotFoundException(patientId)));
    }

    @Override
    @Transactional
    public BillingAccountResponseDTO openAccount(OpenAccountRequestDTO request) {
        if (accountRepository.existsByPatientId(request.patientId())) {
            throw new AccountAlreadyExistsException(request.patientId());
        }
        BillingAccount account = BillingAccount.openFor(request.patientId(), request.currency());
        return accountMapper.toResponse(accountRepository.save(account));
    }
}
