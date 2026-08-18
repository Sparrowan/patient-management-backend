package com.pm.billingservice.grpc;

import java.util.Optional;

import io.grpc.Context;
import io.grpc.Metadata;

/**
 * Carries the propagated actor id (the caller's JWT {@code sub}) through a gRPC call. The
 * {@code x-actor-id} metadata header (set by patient-service) is read by
 * {@link ActorMetadataServerInterceptor} and bound to the gRPC {@link Context}; {@code AuditorAware}
 * then reads it via {@link #currentActor()} to attribute a synchronously-triggered write. gRPC's
 * {@code Context} propagates to the handler thread, so it survives into the JPA save.
 */
public final class ActorContext {

    /** Wire header the client sets and the server reads. */
    static final Metadata.Key<String> ACTOR_ID =
            Metadata.Key.of("x-actor-id", Metadata.ASCII_STRING_MARSHALLER);

    /** In-process carrier for the duration of the call. */
    static final Context.Key<String> ACTOR = Context.key("actor-id");

    private ActorContext() {
    }

    /** The actor propagated on the current gRPC call, if any. */
    public static Optional<String> currentActor() {
        return Optional.ofNullable(ACTOR.get());
    }
}
