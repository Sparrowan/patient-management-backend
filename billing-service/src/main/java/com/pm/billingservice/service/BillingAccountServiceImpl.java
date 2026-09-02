package com.pm.billingservice.service;

import com.pm.billingservice.dto.BillingAccountResponseDTO;
import com.pm.billingservice.dto.CursorPage;
import com.pm.billingservice.dto.LedgerEntryResponseDTO;
import com.pm.billingservice.dto.MoneyMovementRequestDTO;
import com.pm.billingservice.dto.OpenAccountRequestDTO;
import com.pm.billingservice.dto.PagedResponse;
import com.pm.billingservice.exception.AccountAlreadyExistsException;
import com.pm.billingservice.exception.BillingAccountNotFoundException;
import com.pm.billingservice.exception.InvalidCursorException;
import com.pm.billingservice.mapper.BillingAccountMapper;
import com.pm.billingservice.mapper.LedgerEntryMapper;
import com.pm.billingservice.model.BillingAccount;
import com.pm.billingservice.model.EntryType;
import com.pm.billingservice.model.LedgerEntry;
import com.pm.billingservice.pagination.Cursor;
import com.pm.billingservice.pagination.CursorCodec;
import com.pm.billingservice.repository.BillingAccountRepository;
import com.pm.billingservice.repository.LedgerEntryRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BillingAccountServiceImpl implements BillingAccountService {

    private final BillingAccountRepository accountRepository;
    private final LedgerEntryRepository ledgerRepository;
    private final BillingAccountMapper accountMapper;
    private final LedgerEntryMapper ledgerMapper;

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<BillingAccountResponseDTO> getAccounts(Pageable pageable) {
        return PagedResponse.from(accountRepository.findAll(pageable).map(accountMapper::toResponse));
    }

    @Override
    @Transactional(readOnly = true)
    public BillingAccountResponseDTO getAccount(UUID id) {
        return accountMapper.toResponse(findAccountOrThrow(id));
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

    @Override
    @Transactional
    public LedgerEntryResponseDTO credit(
            UUID accountId, MoneyMovementRequestDTO request, String idempotencyKey) {
        return applyMovement(accountId, request, idempotencyKey, EntryType.CREDIT);
    }

    @Override
    @Transactional
    public LedgerEntryResponseDTO debit(
            UUID accountId, MoneyMovementRequestDTO request, String idempotencyKey) {
        return applyMovement(accountId, request, idempotencyKey, EntryType.DEBIT);
    }

    @Override
    @Transactional
    public BillingAccountResponseDTO deactivateForPatient(UUID patientId) {
        BillingAccount account = accountRepository
                .findByPatientId(patientId)
                .orElseThrow(() -> new BillingAccountNotFoundException(patientId));
        account.deactivate();
        return accountMapper.toResponse(accountRepository.save(account));
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<LedgerEntryResponseDTO> getLedger(UUID accountId, Pageable pageable) {
        if (!accountRepository.existsById(accountId)) {
            throw new BillingAccountNotFoundException(accountId);
        }
        return PagedResponse.from(
                ledgerRepository.findByAccountId(accountId, pageable).map(ledgerMapper::toResponse));
    }

    /** Default and hard cap for keyset page size — a bounded page keeps each seek O(limit). */
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    @Override
    @Transactional(readOnly = true)
    public CursorPage<LedgerEntryResponseDTO> getLedgerPage(UUID accountId, String cursor, int limit) {
        if (!accountRepository.existsById(accountId)) {
            throw new BillingAccountNotFoundException(accountId);
        }
        int pageSize = clampLimit(limit);

        // Fetch one extra row: its presence tells us another page exists, without a COUNT.
        Limit fetch = Limit.of(pageSize + 1);
        List<LedgerEntry> rows = (cursor == null || cursor.isBlank())
                ? ledgerRepository.findFirstPage(accountId, fetch)
                : seekAfter(accountId, cursor, fetch);

        boolean hasMore = rows.size() > pageSize;
        List<LedgerEntry> page = hasMore ? rows.subList(0, pageSize) : rows;

        String nextCursor = null;
        if (hasMore) {
            LedgerEntry last = page.get(page.size() - 1);
            nextCursor = CursorCodec.encode(new Cursor(last.getCreatedAt(), last.getId()));
        }
        return CursorPage.of(page.stream().map(ledgerMapper::toResponse).toList(), nextCursor, hasMore);
    }

    private List<LedgerEntry> seekAfter(UUID accountId, String cursor, Limit fetch) {
        Cursor position;
        try {
            position = CursorCodec.decode(cursor);
        } catch (IllegalArgumentException malformed) {
            throw new InvalidCursorException(malformed); // → 400, not a 500
        }
        return ledgerRepository.findPageAfter(accountId, position.createdAt(), position.id(), fetch);
    }

    private int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * Applies a credit or debit atomically: the account balance and the ledger entry are written
     * in the same transaction. Idempotent on the key — a retried request returns the original
     * entry (whose {@code balanceAfter} is stable) without re-applying; the unique key column is
     * the hard guarantee against double-applying concurrent retries.
     */
    private LedgerEntryResponseDTO applyMovement(
            UUID accountId, MoneyMovementRequestDTO request, String idempotencyKey, EntryType type) {
        Optional<LedgerEntry> replay = ledgerRepository.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            return ledgerMapper.toResponse(replay.get());
        }

        // Pessimistic write lock: concurrent movements on this account serialize here rather
        // than racing on the balance and 409-ing via the optimistic @Version check.
        BillingAccount account = accountRepository
                .findByIdForUpdate(accountId)
                .orElseThrow(() -> new BillingAccountNotFoundException(accountId));
        if (type == EntryType.CREDIT) {
            account.credit(request.amount());
        } else {
            account.debit(request.amount());
        }
        accountRepository.save(account);

        LedgerEntry entry = ledgerRepository.save(LedgerEntry.record(
                accountId, type, request.amount(), account.getBalance(), idempotencyKey, request.description()));
        return ledgerMapper.toResponse(entry);
    }

    private BillingAccount findAccountOrThrow(UUID id) {
        return accountRepository.findById(id).orElseThrow(() -> new BillingAccountNotFoundException(id));
    }
}
