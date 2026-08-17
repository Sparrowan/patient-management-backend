package com.pm.billingservice.config;

import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * JPA auditing. {@code @CreatedDate}/{@code @LastModifiedDate} + {@code @CreatedBy}/
 * {@code @LastModifiedBy} on {@link com.pm.billingservice.model.BaseEntity} are populated
 * automatically — the "who" comes from the {@link AuditorAware} below.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaConfig {

    /**
     * The current user for audit stamping: the JWT subject (username) on an authenticated REST call
     * (e.g. an admin crediting an account), else {@code "system"} for the Kafka consumer / gRPC /
     * schedulers, which have no user principal.
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
