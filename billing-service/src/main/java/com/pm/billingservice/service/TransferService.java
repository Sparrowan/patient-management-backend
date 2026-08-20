package com.pm.billingservice.service;

import com.pm.billingservice.dto.TransferRequestDTO;
import com.pm.billingservice.dto.TransferResponseDTO;

/** Moves money between two accounts. */
public interface TransferService {

    /**
     * Transfers {@code amount} from one account to another atomically: both balance changes, the
     * transfer record, and the two double-entry ledger legs commit together or not at all.
     * Idempotent on {@code idempotencyKey} — a retried request returns the original result without
     * moving money again.
     *
     * @throws com.pm.billingservice.exception.BillingAccountNotFoundException if either account is unknown
     * @throws com.pm.billingservice.exception.CurrencyMismatchException       if the accounts differ in currency
     * @throws com.pm.billingservice.exception.InsufficientFundsException      if the source lacks the funds
     * @throws com.pm.billingservice.exception.AccountNotActiveException       if either account is not ACTIVE
     */
    TransferResponseDTO transfer(TransferRequestDTO request, String idempotencyKey);
}
