package com.pm.billingservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.pm.billingservice.grpc.ActorMetadataServerInterceptor;

import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;

/**
 * Registers {@link ActorMetadataServerInterceptor} as a <b>global</b> gRPC server interceptor, so
 * every inbound call binds the propagated actor id to the gRPC context for auditing.
 */
@Configuration
public class GrpcServerInterceptorConfig {

    @GrpcGlobalServerInterceptor
    ActorMetadataServerInterceptor actorMetadataServerInterceptor() {
        return new ActorMetadataServerInterceptor();
    }
}
