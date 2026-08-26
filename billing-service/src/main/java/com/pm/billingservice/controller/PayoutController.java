package com.pm.billingservice.controller;

import com.pm.billingservice.dto.PayoutRequestDTO;
import com.pm.billingservice.dto.PayoutResponseDTO;
import com.pm.billingservice.service.PayoutService;
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
 * HTTP concerns only. A payout is admin-only ({@code @PreAuthorize}) money movement and requires an
 * {@code Idempotency-Key} header. Returns <b>202 Accepted</b>, not 201: the source is debited
 * synchronously but the external settlement is still in flight — the payout comes back
 * {@code PENDING} and the saga finishes it asynchronously.
 */
@RestController
@RequestMapping("/api/v1/billing-accounts/payouts")
@RequiredArgsConstructor
@Tag(name = "Payouts", description = "Pay money out of a billing account to an external destination")
public class PayoutController {

    private final PayoutService payoutService;

    @Operation(
            summary = "Initiate an external payout",
            description = "Debits the source account and records the payout as PENDING; an async saga "
                    + "settles it externally (or compensates on failure). Admin-only. Requires an "
                    + "Idempotency-Key header.")
    @ApiResponse(responseCode = "202", description = "Payout accepted and PENDING (or replayed)")
    @ApiResponse(responseCode = "400", description = "Validation failed / missing Idempotency-Key")
    @ApiResponse(responseCode = "403", description = "Not an admin")
    @ApiResponse(responseCode = "404", description = "Source account is unknown")
    @ApiResponse(responseCode = "409", description = "Source account is not active")
    @ApiResponse(responseCode = "422", description = "Insufficient funds")
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('ADMIN')")
    public PayoutResponseDTO initiate(
            @Valid @RequestBody PayoutRequestDTO request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return payoutService.initiate(request, idempotencyKey);
    }
}
