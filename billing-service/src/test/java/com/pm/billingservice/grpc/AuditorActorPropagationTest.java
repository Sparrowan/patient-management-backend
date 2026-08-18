package com.pm.billingservice.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.pm.billingservice.config.JpaConfig;
import com.pm.billingservice.messaging.ConsumerActorContext;

import io.grpc.Context;

/**
 * The audit "who" resolution: a REST principal wins; absent that, an actor propagated over gRPC is
 * used; absent both, {@code "system"}. Pure unit test — no Spring context — exercising the lambda in
 * {@link JpaConfig#auditorAware()} directly.
 */
@DisplayName("Audit 'who' resolution")
class AuditorActorPropagationTest {

    private final AuditorAware<String> auditorAware = new JpaConfig().auditorAware();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a REST principal (JWT subject) is stamped")
    void restPrincipalWins() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("rest-user", "n/a", List.of()));

        assertThat(auditorAware.getCurrentAuditor()).contains("rest-user");
    }

    @Test
    @DisplayName("no REST principal but a gRPC-propagated actor → the actor is stamped")
    void grpcActorUsedWhenNoRestPrincipal() {
        Context withActor = Context.current().withValue(ActorContext.ACTOR, "grpc-user");

        withActor.run(() -> assertThat(auditorAware.getCurrentAuditor()).contains("grpc-user"));
    }

    @Test
    @DisplayName("no REST/gRPC actor but a Kafka-propagated actor → the actor is stamped")
    void kafkaActorUsedWhenNoRestOrGrpc() {
        ConsumerActorContext.set("kafka-user");
        try {
            assertThat(auditorAware.getCurrentAuditor()).contains("kafka-user");
        } finally {
            ConsumerActorContext.clear();
        }
    }

    @Test
    @DisplayName("neither principal nor propagated actor → \"system\"")
    void systemFallback() {
        assertThat(auditorAware.getCurrentAuditor()).contains("system");
    }
}
