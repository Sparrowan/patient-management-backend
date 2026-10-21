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
import com.pm.billingservice.ledger.LedgerEntryLookup;
import com.pm.billingservice.ledger.LedgerHistoryReader;
import com.pm.billingservice.mapper.BillingAccountMapper;
import com.pm.billingservice.mapper.LedgerEntryMapper;
import com.pm.billingservice.model.BillingAccount;
import com.pm.billingservice.model.EntryType;
import com.pm.billingservice.model.LedgerEntry;
import com.pm.billingservice.model.LedgerRecord;
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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BillingAccountServiceImpl implements BillingAccountService {

    private final BillingAccountRepository accountRepository;
    private final LedgerEntryRepository ledgerRepository;
    private final LedgerEntryLookup ledgerLookup;
    private final LedgerHistoryReader ledgerHistory;
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

    /**
     * {@code READ_COMMITTED}, for the same reason as {@link TransferServiceImpl} and
     * {@link PayoutServiceImpl} — and this path was the one that did <em>not</em> have it.
     *
     * <p>Error 1020 ("Record has changed since last read") needs three things in one REPEATABLE READ
     * transaction: a <b>non-locking</b> read of a row, a concurrent commit to that row, then
     * {@code FOR UPDATE} on it. {@code applyMovement} has exactly that shape — the
     * {@code ledgerLookup} idempotency check is a plain read that opens the snapshot, and the account
     * is locked afterwards. So the pessimistic lock, which was supposed to make concurrent movements
     * <em>serialize</em>, made them <b>error</b> instead.
     *
     * <p>Confirmed in SQL both ways: snapshot-read → concurrent update → locking read fails with
     * 1020, whereas locking-read → concurrent update → locking read merely blocks and succeeds. That
     * second shape is why {@code PayoutSagaWorker} is <em>not</em> affected despite also taking this
     * lock without {@code READ_COMMITTED} — it never reads a row before locking it.
     *
     * <p><b>Found by load test, not by the suite.</b> Twelve concurrent credits to one account
     * returned 4×200 and 8×<b>500</b>. It survived this long because the two paths that move money
     * between accounts were written later, hit 1020 during development, and were fixed — while
     * credit/debit, the oldest and most-used path, had no concurrent-same-account test at all. A
     * contended single account is the normal production shape (payroll out of one corporate account,
     * a utility's collection account on a due date), so this was a latent outage, and a 500 is the
     * worst possible answer here: it is the one status the idempotency layer deliberately does not
     * cache, so the caller is told "unknown" about a money movement.
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LedgerEntryResponseDTO credit(
            UUID accountId, MoneyMovementRequestDTO request, String idempotencyKey) {
        return applyMovement(accountId, request, idempotencyKey, EntryType.CREDIT);
    }

    /** Same isolation, same reason as {@link #credit} — both land in {@code applyMovement}. */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
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
        // Spans both halves of the ledger: an archived movement is still this account's history.
        return PagedResponse.from(ledgerHistory.read(accountId, pageable).map(ledgerMapper::toResponse));
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

        // Fetch one extra row: its presence tells us another page exists, without a COUNT. The read
        // spans both halves of the ledger — a cursor that walks past the seam must keep going into
        // the archive, not stop there.
        List<LedgerRecord> rows = ledgerHistory.readKeyset(accountId, decodeCursor(cursor), pageSize + 1);

        boolean hasMore = rows.size() > pageSize;
        List<LedgerRecord> page = hasMore ? rows.subList(0, pageSize) : rows;

        String nextCursor = null;
        if (hasMore) {
            LedgerRecord last = page.get(page.size() - 1);
            nextCursor = CursorCodec.encode(new Cursor(last.getCreatedAt(), last.getId()));
        }
        return CursorPage.of(page.stream().map(ledgerMapper::toResponse).toList(), nextCursor, hasMore);
    }

    private Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return CursorCodec.decode(cursor);
        } catch (IllegalArgumentException malformed) {
            throw new InvalidCursorException(malformed); // → 400, not a 500
        }
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
        // Both halves of the ledger, hot first — an archived key is still a used key, and the
        // per-table unique constraint will NOT catch it for us. See LedgerEntryLookup.
        Optional<LedgerRecord> replay = ledgerLookup.findByIdempotencyKey(idempotencyKey);
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
