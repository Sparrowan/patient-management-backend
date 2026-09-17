package com.pm.billingservice.service;

import com.pm.billingservice.dto.BillingAccountResponseDTO;
import com.pm.billingservice.dto.LedgerEntryResponseDTO;
import com.pm.billingservice.dto.MoneyMovementRequestDTO;
import com.pm.billingservice.dto.OpenAccountRequestDTO;
import com.pm.billingservice.dto.PagedResponse;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

/** Business operations for billing accounts. Controllers depend on this abstraction (DIP). */
public interface BillingAccountService {

    PagedResponse<BillingAccountResponseDTO> getAccounts(Pageable pageable);

    /** @throws com.pm.billingservice.exception.BillingAccountNotFoundException if none exists */
    BillingAccountResponseDTO getAccount(UUID id);

    /** @throws com.pm.billingservice.exception.BillingAccountNotFoundException if none exists */
    BillingAccountResponseDTO getAccountByPatient(UUID patientId);

    /** @throws com.pm.billingservice.exception.AccountAlreadyExistsException if the patient has one */
    BillingAccountResponseDTO openAccount(OpenAccountRequestDTO request);

    /**
     * Adds funds. Idempotent on {@code idempotencyKey}: a retried key returns the original result
     * without re-applying.
     *
     * @throws com.pm.billingservice.exception.BillingAccountNotFoundException if none exists
     */
    LedgerEntryResponseDTO credit(UUID accountId, MoneyMovementRequestDTO request, String idempotencyKey);

    /**
     * Removes funds. Idempotent on {@code idempotencyKey}.
     *
     * @throws com.pm.billingservice.exception.BillingAccountNotFoundException if none exists
     * @throws com.pm.billingservice.exception.InsufficientFundsException if the balance is too low
     */
    LedgerEntryResponseDTO debit(UUID accountId, MoneyMovementRequestDTO request, String idempotencyKey);

    /** Returns the account's ledger (money-movement history), newest first. */
    PagedResponse<LedgerEntryResponseDTO> getLedger(UUID accountId, Pageable pageable);

    /**
     * Keyset (cursor) pagination of the ledger, newest first — the scalable path for a large, growing
     * history: O(limit) at any depth and stable under concurrent inserts, at the cost of no total
     * count or random page access. Pass {@code cursor = null} for the first page, then echo
     * {@link com.pm.billingservice.dto.CursorPage#nextCursor()} until {@code hasMore} is false.
     */
    com.pm.billingservice.dto.CursorPage<LedgerEntryResponseDTO> getLedgerPage(
            UUID accountId, String cursor, int limit);

    /**
     * Deactivates a patient's account, invoked by the synchronous deletion veto
     * ({@code CloseAccountForPatient} gRPC): closes it if empty, otherwise throws
     * {@link com.pm.billingservice.exception.AccountHasBalanceException} to veto the delete (a funded
     * account must settle first) — financial history is never deleted. Idempotent.
     *
     * @throws com.pm.billingservice.exception.BillingAccountNotFoundException if the patient has none
     */
    BillingAccountResponseDTO deactivateForPatient(UUID patientId);
}
