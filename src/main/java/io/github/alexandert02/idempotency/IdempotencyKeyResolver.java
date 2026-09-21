package io.github.alexandert02.idempotency;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.method.HandlerMethod;

/** Resolves the idempotency key for an annotated request. */
@FunctionalInterface
public interface IdempotencyKeyResolver {

    /** @return the key, or {@code null}/blank to skip idempotency handling */
    String resolveKey(HttpServletRequest request, HandlerMethod handler);
}
