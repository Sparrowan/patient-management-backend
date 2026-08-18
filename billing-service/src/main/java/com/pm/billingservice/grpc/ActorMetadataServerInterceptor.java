package com.pm.billingservice.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

/**
 * Reads the {@code x-actor-id} header patient-service attaches (the calling user's JWT {@code sub})
 * and binds it to the gRPC {@link Context} for the duration of the call, so {@code AuditorAware} can
 * stamp the real user on a synchronously-triggered write (e.g. closing an account during a delete).
 *
 * <p>Trusting the header is safe here: the mTLS channel already authenticates that the caller is
 * patient-service (client-auth REQUIRE), so the asserted id is a trusted identity assertion, not an
 * unverified claim. Absent header → nothing bound → auditing falls back to {@code "system"}.
 */
public class ActorMetadataServerInterceptor implements ServerInterceptor {

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        String actor = headers.get(ActorContext.ACTOR_ID);
        if (actor == null || actor.isBlank()) {
            return next.startCall(call, headers);
        }
        Context context = Context.current().withValue(ActorContext.ACTOR, actor);
        return Contexts.interceptCall(context, call, headers, next);
    }
}
