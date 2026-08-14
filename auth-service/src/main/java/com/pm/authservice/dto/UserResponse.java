package com.pm.authservice.dto;

import java.util.UUID;

/** Public view of a user — never exposes the password hash. */
public record UserResponse(UUID id, String username, String email, String roles) {
}
