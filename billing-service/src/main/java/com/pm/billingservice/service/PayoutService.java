package com.pm.billingservice.service;

import com.pm.billingservice.dto.PayoutRequestDTO;
import com.pm.billingservice.dto.PayoutResponseDTO;

/** Initiates external payouts (the entry point of the payout saga). */
public interface PayoutService {

    /**
     * Debits the source account and records the payout as {@code PENDING} — the synchronous first
     * step of the saga. The external settlement is driven asynchronously afterwards; this method
     * returns as soon as the money has left the local account and the intent is durably recorded.
     * Idempotent on {@code idempotencyKey} — a retried request returns the original payout without
     * debiting again.
     *
     * @throws com.pm.billingservice.exception.BillingAccountNotFoundException if the source account is unknown
     * @throws com.pm.billingservice.exception.InsufficientFundsException      if the source lacks the funds
     * @throws com.pm.billingservice.exception.AccountNotActiveException       if the source is not ACTIVE
     */
    PayoutResponseDTO initiate(PayoutRequestDTO request, String idempotencyKey);
}
