package com.pm.billingservice.controller;

import com.pm.billingservice.dto.BillingAccountResponseDTO;
import com.pm.billingservice.dto.CursorPage;
import com.pm.billingservice.dto.LedgerEntryResponseDTO;
import com.pm.billingservice.dto.MoneyMovementRequestDTO;
import com.pm.billingservice.dto.OpenAccountRequestDTO;
import com.pm.billingservice.dto.PagedResponse;
import com.pm.billingservice.idempotency.Idempotent;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @Operation(
            summary = "Credit an account",
            description = "Adds funds. Requires an Idempotency-Key header; a retried key returns the original result.")
    @ApiResponse(responseCode = "200", description = "Applied (or replayed)")
    @ApiResponse(responseCode = "400", description = "Validation failed / missing Idempotency-Key")
    @ApiResponse(responseCode = "403", description = "Not an admin")
    @ApiResponse(responseCode = "404", description = "No such account")
    @PostMapping("/{id}/credit")
    @PreAuthorize("hasRole('ADMIN')")
    @Idempotent
    public LedgerEntryResponseDTO credit(
            @PathVariable UUID id,
            @Valid @RequestBody MoneyMovementRequestDTO request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return accountService.credit(id, request, idempotencyKey);
    }

    @Operation(
            summary = "Debit an account",
            description = "Removes funds. Requires an Idempotency-Key header. Fails with 422 if funds are insufficient.")
    @ApiResponse(responseCode = "200", description = "Applied (or replayed)")
    @ApiResponse(responseCode = "400", description = "Validation failed / missing Idempotency-Key")
    @ApiResponse(responseCode = "404", description = "No such account")
    @ApiResponse(responseCode = "422", description = "Insufficient funds")
    @ApiResponse(responseCode = "403", description = "Not an admin")
    @PostMapping("/{id}/debit")
    @PreAuthorize("hasRole('ADMIN')")
    public LedgerEntryResponseDTO debit(
            @PathVariable UUID id,
            @Valid @RequestBody MoneyMovementRequestDTO request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return accountService.debit(id, request, idempotencyKey);
    }

    @Operation(summary = "List an account's ledger (money-movement history)")
    @ApiResponse(responseCode = "404", description = "No such account")
    @GetMapping("/{id}/ledger")
    public PagedResponse<LedgerEntryResponseDTO> getLedger(
            @PathVariable UUID id,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return accountService.getLedger(id, pageable);
    }

    @Operation(
            summary = "Page an account's ledger by cursor (keyset)",
            description = "Scalable pagination for a large history: pass no cursor for the first page, "
                    + "then echo nextCursor until hasMore is false. O(limit) at any depth; no total count.")
    @ApiResponse(responseCode = "200", description = "A page of entries, newest first")
    @ApiResponse(responseCode = "400", description = "Malformed cursor")
    @ApiResponse(responseCode = "404", description = "No such account")
    @GetMapping("/{id}/ledger/keyset")
    public CursorPage<LedgerEntryResponseDTO> getLedgerPage(
            @PathVariable UUID id,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return accountService.getLedgerPage(id, cursor, limit);
    }
}
