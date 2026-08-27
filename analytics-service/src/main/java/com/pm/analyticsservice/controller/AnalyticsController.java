package com.pm.analyticsservice.controller;

import com.pm.analyticsservice.dto.DailyRegistrationView;
import com.pm.analyticsservice.dto.RegistrationSummaryView;
import com.pm.analyticsservice.service.AnalyticsQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The query side's HTTP surface — read-only. Every endpoint serves a pre-computed read model, so
 * there's no business logic here: bind, delegate, return the DTO. A frontend reaches these through
 * the gateway with a valid JWT.
 */
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics", description = "Read-only reporting over event-projected read models")
public class AnalyticsController {

    private final AnalyticsQueryService analyticsQueryService;

    @GetMapping("/registrations")
    @Operation(summary = "Registrations per day over an inclusive date range (oldest first)")
    @ApiResponse(responseCode = "200", description = "The registrations time-series")
    public List<DailyRegistrationView> registrations(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return analyticsQueryService.registrationsBetween(from, to);
    }

    @GetMapping("/summary")
    @Operation(summary = "All-time registration totals")
    @ApiResponse(responseCode = "200", description = "Total registrations and number of active days")
    public RegistrationSummaryView summary() {
        return analyticsQueryService.summary();
    }
}
