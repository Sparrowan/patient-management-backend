package com.pm.billingservice.messaging;

import java.util.Optional;

/**
 * Carries the actor propagated on a Kafka event (the staff user who initiated the originating action,
 * e.g. registering a patient) for the duration of the consumer handler, so {@code AuditorAware} can
 * stamp the account it opens as that user instead of {@code "system"}.
 *
 * <p>A {@link ThreadLocal} suffices here (unlike the gRPC path's {@code Context}): a Kafka handler runs
 * synchronously on the listener-container thread, and the write happens on that same thread. Always
 * {@link #clear()} in a {@code finally} so the value never leaks to the next record on a pooled thread.
 */
public final class ConsumerActorContext {

    private static final ThreadLocal<String> ACTOR = new ThreadLocal<>();

    private ConsumerActorContext() {
    }

    public static void set(String actor) {
        ACTOR.set(actor);
    }

    public static void clear() {
        ACTOR.remove();
    }

    /** The actor propagated on the event currently being consumed, if any. */
    public static Optional<String> current() {
        return Optional.ofNullable(ACTOR.get());
    }
}
