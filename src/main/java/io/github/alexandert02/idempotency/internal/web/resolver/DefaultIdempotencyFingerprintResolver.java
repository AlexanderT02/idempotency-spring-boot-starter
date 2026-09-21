package io.github.alexandert02.idempotency.internal.web.resolver;

import io.github.alexandert02.idempotency.IdempotencyFingerprintResolver;
import io.github.alexandert02.idempotency.internal.web.Sha256Digest;
import io.github.alexandert02.idempotency.internal.web.wrapper.CachedBodyRequestWrapper;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.util.WebUtils;

import static java.util.Objects.isNull;

/**
 * Creates the default request fingerprint.
 *
 * <p>The digest contains, in this fixed order, the HTTP method, request URI, raw query string,
 * content type, and raw request body. Missing query strings and content types are represented by
 * an empty value. The handler is intentionally not included because the operation is already
 * part of the idempotency store key.
 */
public final class DefaultIdempotencyFingerprintResolver implements IdempotencyFingerprintResolver {

    @Override
    public String resolveFingerprint(HttpServletRequest request, HandlerMethod handler) {
        byte[] method = Sha256Digest.utf8(request.getMethod());
        byte[] requestUri = Sha256Digest.utf8(request.getRequestURI());
        byte[] queryString = Sha256Digest.utf8(valueOrEmpty(request.getQueryString()));
        byte[] contentType = Sha256Digest.utf8(valueOrEmpty(request.getContentType()));
        byte[] body = requestBody(request);

        return Sha256Digest.hash(
                method,
                requestUri,
                queryString,
                contentType,
                body);
    }

    private static byte[] requestBody(HttpServletRequest request) {
        CachedBodyRequestWrapper cached = WebUtils.getNativeRequest(request, CachedBodyRequestWrapper.class);
        return isNull(cached) ? new byte[0] : cached.getBody();
    }

    private static String valueOrEmpty(String value) {
        return isNull(value) ? "" : value;
    }
}
