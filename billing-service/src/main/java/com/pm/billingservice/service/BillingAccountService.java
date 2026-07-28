package com.pm.billingservice.service;

import com.pm.billingservice.dto.BillingAccountResponseDTO;
import com.pm.billingservice.dto.OpenAccountRequestDTO;
import com.pm.billingservice.dto.PagedResponse;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

/** Business operations for billing accounts. Controllers depend on this abstraction (DIP). */
public interface BillingAccountService {

    /** Returns a page of billing accounts. */
    PagedResponse<BillingAccountResponseDTO> getAccounts(Pageable pageable);

    /**
     * Returns a single account.
     *
     * @throws com.pm.billingservice.exception.BillingAccountNotFoundException if none exists
     */
    BillingAccountResponseDTO getAccount(UUID id);

    /**
     * Returns the account for a patient.
     *
     * @throws com.pm.billingservice.exception.BillingAccountNotFoundException if none exists
     */
    BillingAccountResponseDTO getAccountByPatient(UUID patientId);

    /**
     * Opens a new billing account for a patient.
     *
     * @throws com.pm.billingservice.exception.AccountAlreadyExistsException if the patient already
     *     has one
     */
    BillingAccountResponseDTO openAccount(OpenAccountRequestDTO request);
}
