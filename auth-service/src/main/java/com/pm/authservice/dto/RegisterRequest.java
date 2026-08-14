package com.pm.authservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Signup payload. Validated before it reaches the service (400 on failure). */
public record RegisterRequest(
        @NotBlank @Size(max = 100) String username,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 100) String password) {
}
