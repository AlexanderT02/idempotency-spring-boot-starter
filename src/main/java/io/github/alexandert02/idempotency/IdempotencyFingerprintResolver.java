package io.github.alexandert02.idempotency;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.method.HandlerMethod;

/** Creates the fingerprint used to detect a reused key with different request data. */
@FunctionalInterface
public interface IdempotencyFingerprintResolver {

    /**
     * Returns a stable fingerprint for the request and endpoint.
     *
     * @return a non-null fingerprint
     */
    String resolveFingerprint(HttpServletRequest request, HandlerMethod handler);
}
