package com.pm.billingservice.config;

import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.pm.billingservice.grpc.ActorContext;

/**
 * JPA auditing. {@code @CreatedDate}/{@code @LastModifiedDate} + {@code @CreatedBy}/
 * {@code @LastModifiedBy} on {@link com.pm.billingservice.model.BaseEntity} are populated
 * automatically — the "who" comes from the {@link AuditorAware} below.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaConfig {

    /**
     * The current user for audit stamping, in priority order:
     * <ol>
     *   <li>the JWT subject on an authenticated REST call (e.g. an admin crediting an account);</li>
     *   <li>else the actor propagated over gRPC (e.g. the user who triggered a delete → account close),
     *       read from the gRPC {@link ActorContext};</li>
     *   <li>else {@code "system"} — the Kafka consumer opening an account, or schedulers, which have
     *       no originating principal.</li>
     * </ol>
     * A REST call runs on a servlet thread (SecurityContext set, gRPC context empty); a gRPC call runs
     * on a gRPC thread (the reverse) — so the two sources never collide.
     */
    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null
                    && authentication.isAuthenticated()
                    && !(authentication instanceof AnonymousAuthenticationToken)) {
                return Optional.of(authentication.getName());
            }
            return Optional.of(ActorContext.currentActor().orElse("system"));
        };
    }
}
