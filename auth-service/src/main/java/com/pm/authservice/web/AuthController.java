package com.pm.authservice.web;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pm.authservice.dto.LoginRequest;
import com.pm.authservice.dto.RegisterRequest;
import com.pm.authservice.dto.TokenResponse;
import com.pm.authservice.dto.UserResponse;
import com.pm.authservice.token.TokenService;
import com.pm.authservice.user.UserService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Auth endpoints. {@code /register} creates a user; {@code /login} verifies credentials via
 * {@code authenticationManager.authenticate(...)} (throws {@code AuthenticationException} → 401) and
 * mints a JWT. The login identifier may be a username or an email.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;
    private final UserService userService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return userService.register(request);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.usernameOrEmail(), request.password()));
        return tokenService.issue(authentication);
    }
}
