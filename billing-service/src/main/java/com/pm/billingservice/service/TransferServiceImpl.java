package com.pm.billingservice.service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.pm.billingservice.dto.TransferRequestDTO;
import com.pm.billingservice.dto.TransferResponseDTO;
import com.pm.billingservice.exception.BillingAccountNotFoundException;
import com.pm.billingservice.exception.CurrencyMismatchException;
import com.pm.billingservice.mapper.TransferMapper;
import com.pm.billingservice.model.BillingAccount;
import com.pm.billingservice.model.EntryType;
import com.pm.billingservice.model.LedgerEntry;
import com.pm.billingservice.model.Transfer;
import com.pm.billingservice.repository.BillingAccountRepository;
import com.pm.billingservice.repository.LedgerEntryRepository;
import com.pm.billingservice.repository.TransferRepository;

import lombok.RequiredArgsConstructor;

/**
 * Local ACID transfer — deliberately <b>not</b> a saga: both accounts live in this one database, so
 * one transaction gives atomicity for free (a saga would only be needed if a leg crossed a boundary
 * we couldn't span with a transaction). The interesting parts are the concurrency and correctness
 * concerns a single credit/debit doesn't have: deadlock-safe lock ordering, double-entry, idempotency.
 */
@Service
@RequiredArgsConstructor
public class TransferServiceImpl implements TransferService {

    private final BillingAccountRepository accountRepository;
    private final LedgerEntryRepository ledgerRepository;
    private final TransferRepository transferRepository;
    private final TransferMapper transferMapper;

    /**
     * {@code READ_COMMITTED}, not the MariaDB default {@code REPEATABLE_READ}: under REPEATABLE READ a
     * transaction takes a snapshot at its first read, and a later {@code SELECT … FOR UPDATE} on a row a
     * concurrent transfer has since modified fails with MariaDB error 1020 ("Record has changed since
     * last read"). READ COMMITTED reads the latest committed row per statement, so the locking read
     * always locks the current version — the right isolation for lock-based money movement.
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResponseDTO transfer(TransferRequestDTO request, String idempotencyKey) {
        // Idempotent replay: a retried transfer returns the original record, never moves money twice.
        Optional<Transfer> replay = transferRepository.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            return transferMapper.toResponse(replay.get());
        }

        UUID from = request.fromAccountId();
        UUID to = request.toAccountId();

        // Deadlock avoidance: acquire the two write locks in a fixed order (by account id),
        // independent of transfer direction — so concurrent A->B and B->A can't form a lock cycle
        // (one waiting on A holding B while the other waits on B holding A). Both always lock the
        // lower id first, so they serialize cleanly instead of deadlocking.
        UUID firstId = from.compareTo(to) <= 0 ? from : to;
        UUID secondId = firstId.equals(from) ? to : from;
        BillingAccount first = lockAccount(firstId);
        BillingAccount second = lockAccount(secondId);

        BillingAccount source = first.getId().equals(from) ? first : second;
        BillingAccount destination = source == first ? second : first;

        if (!source.getCurrency().equals(destination.getCurrency())) {
            throw new CurrencyMismatchException(
                    source.getId(), source.getCurrency(), destination.getId(), destination.getCurrency());
        }

        BigDecimal amount = request.amount();
        source.debit(amount);        // InsufficientFundsException -> 422; AccountNotActiveException -> 409
        destination.credit(amount);  // AccountNotActiveException -> 409
        accountRepository.save(source);
        accountRepository.save(destination);

        Transfer transfer = transferRepository.save(Transfer.record(
                from, to, amount, source.getCurrency(), idempotencyKey, request.description()));

        // Double-entry: a DEBIT leg on the source and a matching CREDIT leg on the destination, both
        // tagged with the transfer id. Per-leg idempotency keys derive from the request key so the
        // ledger's unique-key constraint also backstops a double-apply.
        ledgerRepository.save(LedgerEntry.transferLeg(source.getId(), EntryType.DEBIT, amount,
                source.getBalance(), idempotencyKey + ":debit", request.description(), transfer.getId()));
        ledgerRepository.save(LedgerEntry.transferLeg(destination.getId(), EntryType.CREDIT, amount,
                destination.getBalance(), idempotencyKey + ":credit", request.description(), transfer.getId()));

        return transferMapper.toResponse(transfer);
    }

    private BillingAccount lockAccount(UUID id) {
        return accountRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BillingAccountNotFoundException(id));
    }
}
