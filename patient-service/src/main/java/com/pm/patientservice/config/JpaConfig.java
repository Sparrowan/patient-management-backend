package com.pm.patientservice.config;

import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * JPA auditing. {@code @CreatedDate}/{@code @LastModifiedDate} on {@link
 * com.pm.patientservice.model.BaseEntity} are populated automatically; {@code @CreatedBy}/
 * {@code @LastModifiedBy} come from the {@link AuditorAware} below — the "who" behind every change.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaConfig {

    /**
     * The current user for audit stamping: the JWT subject (username) when a request is
     * authenticated, else {@code "system"} for background writes (schedulers, etc.).
     */
    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null
                    || !authentication.isAuthenticated()
                    || authentication instanceof AnonymousAuthenticationToken) {
                return Optional.of("system");
            }
            return Optional.of(authentication.getName());
        };
    }
}
