package com.pm.billingservice.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a POST handler as idempotent: a client that sends the same {@code Idempotency-Key} on a retry
 * gets the original response replayed instead of the operation running twice.
 *
 * <p>Opt-in by design. {@code GET}/{@code PUT}/{@code DELETE} are already idempotent by HTTP
 * semantics; this only covers non-idempotent {@code POST}s where a retry would otherwise double-apply
 * (open an account, move money, initiate a payout). The {@code IdempotencyInterceptor} reads this
 * marker off the matched handler method and, when present, requires the {@code Idempotency-Key} header
 * and runs the claim → capture → replay flow around the request.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
}
