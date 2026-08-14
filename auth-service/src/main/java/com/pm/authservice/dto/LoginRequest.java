package com.pm.authservice.dto;

import jakarta.validation.constraints.NotBlank;

/** Login credentials. {@code usernameOrEmail} accepts either the username or the email address. */
public record LoginRequest(@NotBlank String usernameOrEmail, @NotBlank String password) {
}
