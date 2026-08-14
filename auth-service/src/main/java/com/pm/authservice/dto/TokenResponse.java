package com.pm.authservice.dto;

/** The issued access token, in the usual OAuth2-style shape. */
public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds) {
}
