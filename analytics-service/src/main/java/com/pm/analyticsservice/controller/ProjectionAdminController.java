package com.pm.analyticsservice.controller;

import com.pm.analyticsservice.service.ProjectionRebuildService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operational actions on the read models (as opposed to the read-only query surface). Rebuild is
 * admin-only — enforced by path in {@code SecurityConfig} (a filter-level 403), not {@code @PreAuthorize},
 * so it needs no method-security exception mapping.
 */
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics admin", description = "Operational actions on the projected read models")
public class ProjectionAdminController {

    private final ProjectionRebuildService rebuildService;

    @PostMapping("/rebuild")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Wipe the read models and rebuild them by replaying the whole event log (admin only)")
    @ApiResponse(responseCode = "202", description = "Rebuild started; read models are eventually consistent while replay runs")
    public void rebuild() {
        rebuildService.rebuild();
    }
}
