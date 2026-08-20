package com.pm.billingservice.controller;

import com.pm.billingservice.dto.TransferRequestDTO;
import com.pm.billingservice.dto.TransferResponseDTO;
import com.pm.billingservice.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP concerns only. Money movement is admin-only ({@code @PreAuthorize}), like credit/debit, and
 * requires an {@code Idempotency-Key} header so a retried request can't move money twice.
 */
@RestController
@RequestMapping("/api/v1/billing-accounts/transfers")
@RequiredArgsConstructor
@Tag(name = "Transfers", description = "Move money between billing accounts")
public class TransferController {

    private final TransferService transferService;

    @Operation(
            summary = "Transfer money between two accounts",
            description = "Debits the source and credits the destination atomically (double-entry). "
                    + "Admin-only. Requires an Idempotency-Key header.")
    @ApiResponse(responseCode = "201", description = "Transfer applied (or replayed)")
    @ApiResponse(responseCode = "400", description = "Validation failed / missing Idempotency-Key / same account")
    @ApiResponse(responseCode = "403", description = "Not an admin")
    @ApiResponse(responseCode = "404", description = "Either account is unknown")
    @ApiResponse(responseCode = "409", description = "An account is not active")
    @ApiResponse(responseCode = "422", description = "Insufficient funds or currency mismatch")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public TransferResponseDTO transfer(
            @Valid @RequestBody TransferRequestDTO request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return transferService.transfer(request, idempotencyKey);
    }
}
