package com.pm.billingservice.idempotency;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the {@link IdempotencyInterceptor} on the API routes. The interceptor itself is inert on
 * any handler not marked {@link Idempotent}, so scoping to {@code /api/**} is just to avoid running it
 * on actuator/swagger paths.
 */
@Configuration
@RequiredArgsConstructor
public class IdempotencyWebConfig implements WebMvcConfigurer {

    private final IdempotencyInterceptor idempotencyInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(idempotencyInterceptor).addPathPatterns("/api/**");
    }
}
