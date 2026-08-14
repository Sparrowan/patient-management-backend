package com.pm.authservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Web security rules. The {@link SecurityFilterChain} is Spring's request-rule pipeline (the rough
 * equivalent of Laravel middleware / an Express auth middleware stack). This service is a token
 * <em>issuer</em>, so it's stateless (no session), CSRF is off, and only login + JWKS are public.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/auth/register", "/api/v1/auth/login", "/oauth2/jwks", "/actuator/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // Users now come from the database via DatabaseUserDetailsService (a @Service bean); the
    // AuthenticationManager below wires it together with the PasswordEncoder automatically.

    /**
     * Exposes Boot's auto-configured {@link AuthenticationManager} (wired from the
     * {@code UserDetailsService} + {@code PasswordEncoder} beans) so the login endpoint can call it.
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }
}
