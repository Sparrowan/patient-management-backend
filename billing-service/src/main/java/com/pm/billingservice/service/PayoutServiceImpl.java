package com.pm.billingservice.service;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.pm.billingservice.dto.PayoutRequestDTO;
import com.pm.billingservice.dto.PayoutResponseDTO;
import com.pm.billingservice.exception.BillingAccountNotFoundException;
import com.pm.billingservice.mapper.PayoutMapper;
import com.pm.billingservice.model.BillingAccount;
import com.pm.billingservice.model.EntryType;
import com.pm.billingservice.model.LedgerEntry;
import com.pm.billingservice.model.Payout;
import com.pm.billingservice.repository.BillingAccountRepository;
import com.pm.billingservice.repository.LedgerEntryRepository;
import com.pm.billingservice.repository.PayoutRepository;

import lombok.RequiredArgsConstructor;

/**
 * Initiates a payout: the <b>synchronous first step</b> of the orchestrated saga. It debits the
 * source account locally (ACID) and records the payout as {@code PENDING}; the external settlement
 * is left to the async coordinator. Debiting up front means the funds are reserved the moment the
 * request is accepted — the customer can't spend money that's already on its way out — and a
 * failure to settle is later resolved by compensation (crediting the debit back), not by pretending
 * the money never moved.
 */
@Service
@RequiredArgsConstructor
public class PayoutServiceImpl implements PayoutService {

    private final BillingAccountRepository accountRepository;
    private final LedgerEntryRepository ledgerRepository;
    private final PayoutRepository payoutRepository;
    private final PayoutMapper payoutMapper;

    /**
     * {@code READ_COMMITTED} for the same reason as {@code TransferServiceImpl}: under MariaDB's
     * default REPEATABLE READ a {@code SELECT … FOR UPDATE} on a row a concurrent movement has since
     * modified fails with error 1020; READ COMMITTED locks the latest committed row.
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PayoutResponseDTO initiate(PayoutRequestDTO request, String idempotencyKey) {
        // Idempotent replay: a retried initiate returns the original payout, never debits twice.
        Optional<Payout> replay = payoutRepository.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            return payoutMapper.toResponse(replay.get());
        }

        BillingAccount source = accountRepository.findByIdForUpdate(request.sourceAccountId())
                .orElseThrow(() -> new BillingAccountNotFoundException(request.sourceAccountId()));

        BigDecimal amount = request.amount();
        source.debit(amount); // InsufficientFundsException -> 422; AccountNotActiveException -> 409
        accountRepository.save(source);

        Payout payout = payoutRepository.save(Payout.initiate(source.getId(), request.destinationReference(),
                amount, source.getCurrency(), idempotencyKey, request.description()));

        // The DEBIT leg records the money leaving now; a compensating CREDIT is written if the saga
        // later reverses. The per-leg idempotency key derives from the request key so the ledger's
        // unique-key constraint also backstops a double-apply.
        ledgerRepository.save(LedgerEntry.payoutLeg(source.getId(), EntryType.DEBIT, amount,
                source.getBalance(), idempotencyKey + ":debit", request.description(), payout.getId()));

        return payoutMapper.toResponse(payout);
    }
}
