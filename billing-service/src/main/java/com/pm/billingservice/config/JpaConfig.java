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
import com.pm.billingservice.messaging.ConsumerActorContext;

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
     *       from the gRPC {@link ActorContext};</li>
     *   <li>else the actor carried on a Kafka event (e.g. the user who registered the patient whose
     *       account is being opened), from the {@link ConsumerActorContext};</li>
     *   <li>else {@code "system"} — schedulers and anything with no originating principal.</li>
     * </ol>
     * Each entry point runs on its own thread and populates only its own source, so they never collide:
     * REST → servlet thread (SecurityContext), gRPC → gRPC thread (gRPC context), Kafka → listener
     * thread (thread-local).
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
            return Optional.of(ActorContext.currentActor()
                    .or(ConsumerActorContext::current)
                    .orElse("system"));
        };
    }
}
