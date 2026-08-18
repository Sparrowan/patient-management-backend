package com.pm.patientservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.pm.patientservice.grpc.ActorMetadataClientInterceptor;

import net.devh.boot.grpc.client.interceptor.GrpcGlobalClientInterceptor;

/**
 * Registers {@link ActorMetadataClientInterceptor} as a <b>global</b> gRPC client interceptor, so
 * every outbound call (currently just the billing deletion veto) carries the caller's identity for
 * downstream audit. {@code @GrpcGlobalClientInterceptor} is net.devh's marker for "apply to all
 * channels".
 */
@Configuration
public class GrpcClientInterceptorConfig {

    @GrpcGlobalClientInterceptor
    ActorMetadataClientInterceptor actorMetadataClientInterceptor() {
        return new ActorMetadataClientInterceptor();
    }
}
