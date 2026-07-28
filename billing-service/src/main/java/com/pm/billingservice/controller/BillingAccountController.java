package com.pm.billingservice.controller;

import com.pm.billingservice.dto.BillingAccountResponseDTO;
import com.pm.billingservice.dto.OpenAccountRequestDTO;
import com.pm.billingservice.dto.PagedResponse;
import com.pm.billingservice.service.BillingAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP concerns only: bind, validate, delegate, return a DTO. Error codes come from the global
 * exception handler. Versioned under {@code /api/v1}.
 */
@RestController
@RequestMapping("/api/v1/billing-accounts")
@RequiredArgsConstructor
@Tag(name = "Billing Accounts", description = "Manage patient billing accounts")
public class BillingAccountController {

    private final BillingAccountService accountService;

    @Operation(summary = "List billing accounts", description = "Paginated. e.g. ?page=0&size=20&sort=createdAt,desc")
    @GetMapping
    public PagedResponse<BillingAccountResponseDTO> getAccounts(
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return accountService.getAccounts(pageable);
    }

    @Operation(summary = "Get a billing account by id")
    @ApiResponse(responseCode = "404", description = "No such account")
    @GetMapping("/{id}")
    public BillingAccountResponseDTO getAccount(@PathVariable UUID id) {
        return accountService.getAccount(id);
    }

    @Operation(summary = "Get a patient's billing account")
    @ApiResponse(responseCode = "404", description = "No account for this patient")
    @GetMapping("/by-patient/{patientId}")
    public BillingAccountResponseDTO getAccountByPatient(@PathVariable UUID patientId) {
        return accountService.getAccountByPatient(patientId);
    }

    @Operation(summary = "Open a billing account for a patient")
    @ApiResponse(responseCode = "201", description = "Opened")
    @ApiResponse(responseCode = "400", description = "Validation failed")
    @ApiResponse(responseCode = "409", description = "Account already exists for this patient")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BillingAccountResponseDTO openAccount(@Valid @RequestBody OpenAccountRequestDTO request) {
        return accountService.openAccount(request);
    }
}
