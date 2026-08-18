package com.pm.patientservice.grpc;

import java.util.Optional;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

/**
 * Propagates the calling user's identity to billing on every outbound gRPC call, as an
 * {@code x-actor-id} metadata header carrying the JWT {@code sub}. This lets billing audit a
 * synchronously-triggered write (e.g. closing an account during a patient delete) as the real user
 * instead of {@code "system"}.
 *
 * <p>Only the <b>id</b> is sent, not the token — billing makes no authorization decision on the
 * caller's roles (that already happened at the gateway + here), and the mTLS channel already
 * authenticates that the caller <em>is</em> patient-service. So this is a trusted identity assertion
 * over an authenticated channel, not credential forwarding. Runs on the request thread, so the
 * {@code SecurityContext} still holds the user.
 */
public class ActorMetadataClientInterceptor implements ClientInterceptor {

    static final Metadata.Key<String> ACTOR_ID =
            Metadata.Key.of("x-actor-id", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                currentActorId().ifPresent(id -> headers.put(ACTOR_ID, id));
                super.start(responseListener, headers);
            }
        };
    }

    private static Optional<String> currentActorId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        return Optional.ofNullable(auth.getName());
    }
}
