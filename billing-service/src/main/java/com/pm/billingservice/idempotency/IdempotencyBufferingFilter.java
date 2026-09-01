package com.pm.billingservice.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Installs the re-readable request and capturable response wrappers the idempotency interceptor needs.
 * A {@link org.springframework.web.servlet.HandlerInterceptor} can read the response but can't
 * <em>replace</em> it with a caching wrapper — that has to happen out here in a filter, before the
 * handler writes anything.
 *
 * <p>Only wraps {@code POST}s (the only method the interceptor treats as idempotent), so GETs pay
 * nothing. Ordered just after {@link com.pm.billingservice.web.CorrelationIdFilter} so idempotency
 * logs still carry the request id. The {@code finally} flushes the buffered body to the real response.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class IdempotencyBufferingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        CachedBodyHttpServletRequest bufferedRequest = new CachedBodyHttpServletRequest(request);
        ContentCachingResponseWrapper cachingResponse = new ContentCachingResponseWrapper(response);
        try {
            filterChain.doFilter(bufferedRequest, cachingResponse);
        } finally {
            // Flush the captured body out to the real response — without this the client gets nothing.
            cachingResponse.copyBodyToResponse();
        }
    }
}
