package com.pm.patientservice.controller;

import com.pm.patientservice.dto.PagedResponse;
import com.pm.patientservice.dto.PatientRequestDTO;
import com.pm.patientservice.dto.PatientResponseDTO;
import com.pm.patientservice.dto.PatientUpdateRequestDTO;
import com.pm.patientservice.service.PatientService;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP concerns only: bind, validate, delegate, return a DTO. Success status codes are the
 * default 200 unless declared with {@code @ResponseStatus}; error codes (404/409/400) are
 * produced by the global exception handler, never by branching here. Versioned under
 * {@code /api/v1} so the contract can evolve without breaking existing clients.
 */
@RestController
@RequestMapping("/api/v1/patients")
@RequiredArgsConstructor
@Tag(name = "Patients", description = "Manage patient records")
public class PatientController {

    private final PatientService patientService;

    @Operation(summary = "List patients", description = "Paginated. e.g. ?page=0&size=20&sort=name,asc")
    @GetMapping
    public PagedResponse<PatientResponseDTO> getPatients(
            @ParameterObject @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC)
                    Pageable pageable) {
        return patientService.getPatients(pageable);
    }

    @Operation(summary = "Get a patient", description = "Supports ETag: send If-None-Match to get 304 if unchanged.")
    @ApiResponse(responseCode = "404", description = "No such patient")
    @ApiResponse(responseCode = "304", description = "Not modified (ETag matched)")
    @GetMapping("/{id}")
    public ResponseEntity<PatientResponseDTO> getPatient(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        PatientResponseDTO patient = patientService.getPatient(id);
        // The entity version is a natural ETag: it changes exactly when the resource changes.
        String etag = "\"" + patient.version() + "\"";
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).body(patient);
    }

    @Operation(summary = "Create a patient")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "400", description = "Validation failed")
    @ApiResponse(responseCode = "409", description = "Email already in use")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PatientResponseDTO createPatient(@Valid @RequestBody PatientRequestDTO request) {
        return patientService.createPatient(request);
    }

    @Operation(
            summary = "Update a patient",
            description = "Full replacement. Requires the current version for optimistic concurrency.")
    @ApiResponse(responseCode = "404", description = "No such patient")
    @ApiResponse(responseCode = "400", description = "Validation failed")
    @ApiResponse(responseCode = "409", description = "Email already in use, or stale version")
    @PutMapping("/{id}")
    public PatientResponseDTO updatePatient(
            @PathVariable UUID id, @Valid @RequestBody PatientUpdateRequestDTO request) {
        return patientService.updatePatient(id, request);
    }

    @Operation(summary = "Delete a patient", description = "Soft delete — the record is retained, not removed.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "No such patient")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePatient(@PathVariable UUID id) {
        patientService.deletePatient(id);
    }
}
