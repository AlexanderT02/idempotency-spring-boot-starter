package io.github.alexandert02.idempotency.internal.web;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.Objects.requireNonNull;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.github.alexandert02.idempotency.Idempotent;
import io.github.alexandert02.idempotency.IdempotencyFingerprintResolver;
import io.github.alexandert02.idempotency.autoconfigure.IdempotencyProperties;
import io.github.alexandert02.idempotency.internal.metrics.IdempotencyMetrics;
import io.github.alexandert02.idempotency.store.CapturedResponse;
import io.github.alexandert02.idempotency.store.ClaimResult;
import io.github.alexandert02.idempotency.store.IdempotencyRecord;
import io.github.alexandert02.idempotency.store.IdempotencyStore;

import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.util.WebUtils;

/** Claims requests and stores or replays their responses. */
public final class IdempotencyInterceptor implements AsyncHandlerInterceptor {

    private static final String REPLAY_HEADER = "Idempotency-Replayed";
    private static final String ATTR_CONTEXT = IdempotencyInterceptor.class.getName() + ".CONTEXT";
    private static final String ATTR_ASYNC_SKIPPED = IdempotencyInterceptor.class.getName() + ".ASYNC_SKIPPED";

    private final IdempotencyStore store;
    private final IdempotencyProperties properties;
    private final IdempotencyMetrics metrics;
    private final IdempotencyFingerprintResolver fingerprintResolver;

    public IdempotencyInterceptor(IdempotencyStore store, IdempotencyProperties properties,
                                  IdempotencyMetrics metrics,
                                  IdempotencyFingerprintResolver fingerprintResolver) {
        this.store = store;
        this.properties = properties;
        this.metrics = metrics;
        this.fingerprintResolver = fingerprintResolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        if (Boolean.TRUE.equals(request.getAttribute(ATTR_ASYNC_SKIPPED))) {
            return true;
        }

        Idempotent annotation = method.getMethodAnnotation(Idempotent.class);
        if (isNull(annotation)) {
            return true;
        }

        String key = (String) request.getAttribute(IdempotencyFilter.ATTR_KEY);
        if (!StringUtils.hasText(key)) {
            return true;
        }

        Duration ttl = resolveTtl(annotation);
        String fingerprint = requireNonNull(
                fingerprintResolver.resolveFingerprint(request, method),
                "fingerprintResolver must return a non-null fingerprint");
        ClaimResult claim = tryClaim(key, fingerprint, ttl);
        if (!claim.acquired()) {
            return handleExisting(claim.existing(), fingerprint, response);
        }

        metrics.recordEvent("acquired");
        request.setAttribute(ATTR_CONTEXT, new ClaimContext(key, fingerprint, ttl, System.nanoTime()));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        ClaimContext context = (ClaimContext) request.getAttribute(ATTR_CONTEXT);
        if (isNull(context)) {
            return;
        }

        int status = responseStatus(response);
        if (nonNull(ex) || !shouldCache(status)) {
            release(context);
            return;
        }

        BoundedResponseWrapper wrapper =
                WebUtils.getNativeResponse(response, BoundedResponseWrapper.class);
        if (nonNull(wrapper) && wrapper.limitExceeded()) {
            metrics.recordEvent("response_too_large");
            release(context);
            return;
        }

        complete(context, capture(response, status));
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
                                               Object handler) {
        ClaimContext context = (ClaimContext) request.getAttribute(ATTR_CONTEXT);
        if (nonNull(context)) {
            request.removeAttribute(ATTR_CONTEXT);
            request.setAttribute(ATTR_ASYNC_SKIPPED, true);
            metrics.recordEvent("async_released");
            release(context);
        }
    }

    private ClaimResult tryClaim(String key, String fingerprint, Duration ttl) {
        try {
            return store.tryClaim(key, fingerprint, ttl);
        } catch (RuntimeException exception) {
            metrics.recordStoreError("claim");
            throw exception;
        }
    }

    private boolean handleExisting(IdempotencyRecord existing, String fingerprint,
                                   HttpServletResponse response) throws IOException {
        if (!fingerprint.equals(existing.fingerprint())) {
            metrics.recordEvent("fingerprint_mismatch");
            ProblemResponseWriter.write(response, HttpServletResponse.SC_UNPROCESSABLE_CONTENT,
                    "Unprocessable Content",
                    "The idempotency key was already used for a different request");
        } else if (existing.isCompleted()) {
            replay(existing.response(), response);
            metrics.recordEvent("replayed");
        } else {
            metrics.recordEvent("in_progress");
            response.setHeader("Retry-After", "1");
            ProblemResponseWriter.write(response, HttpServletResponse.SC_CONFLICT, "Conflict",
                    "A request with this idempotency key is still in progress");
        }
        return false;
    }

    private void complete(ClaimContext context, CapturedResponse response) {
        IdempotencyRecord completedWith = IdempotencyRecord.inProgress(context.fingerprint()).completedWith(response);
        try {
            store.complete(context.key(), completedWith, context.ttl());
            metrics.recordEvent("completed");
            recordExecution(context, "completed");
        } catch (RuntimeException exception) {
            metrics.recordStoreError("complete");
            recordExecution(context, "store_error");
            throw exception;
        }
    }

    private void release(ClaimContext context) {
        try {
            store.release(context.key());
            metrics.recordEvent("released");
            recordExecution(context, "released");
        } catch (RuntimeException exception) {
            metrics.recordStoreError("release");
            recordExecution(context, "store_error");
            throw exception;
        }
    }

    private CapturedResponse capture(HttpServletResponse response, int status) {
        BoundedResponseWrapper wrapper =
                WebUtils.getNativeResponse(response, BoundedResponseWrapper.class);
        HttpServletResponse source = nonNull(wrapper) ? wrapper : response;
        Map<String, List<String>> headers = new LinkedHashMap<>();
        source.getHeaderNames().forEach(name -> headers.put(name, List.copyOf(source.getHeaders(name))));

        String contentType = source.getContentType();
        if (nonNull(contentType) && headers.keySet().stream().noneMatch("Content-Type"::equalsIgnoreCase)) {
            headers.put("Content-Type", List.of(contentType));
        }

        byte[] body = isNull(wrapper) ? new byte[0] : wrapper.capturedBody();
        return new CapturedResponse(status, headers, body);
    }

    private void replay(CapturedResponse captured, HttpServletResponse response) throws IOException {
        response.setStatus(captured.status());
        captured.headers().forEach((name, values) -> replayHeader(response, name, values));
        response.setHeader(REPLAY_HEADER, "true");

        byte[] body = captured.body();
        if (body.length > 0) {
            response.getOutputStream().write(body);
        }
    }

    private void replayHeader(HttpServletResponse response, String name, List<String> values) {
        if (name.equalsIgnoreCase("Content-Length") || values.isEmpty()) {
            return;
        }
        response.setHeader(name, values.getFirst());
        values.stream().skip(1).forEach(value -> response.addHeader(name, value));
    }

    private int responseStatus(HttpServletResponse response) {
        return response.getStatus();
    }

    private boolean shouldCache(int status) {
        if (status >= 500) {
            return properties.cache().serverErrors();
        }
        if (status >= 400) {
            return properties.cache().clientErrors();
        }
        return true;
    }

    private Duration resolveTtl(Idempotent annotation) {
        if (annotation.ttlSeconds() < 0) {
            throw new IllegalStateException("@Idempotent ttlSeconds must not be negative");
        }
        return annotation.ttlSeconds() == 0
                ? properties.ttl()
                : Duration.ofSeconds(annotation.ttlSeconds());
    }

    private void recordExecution(ClaimContext context, String outcome) {
        metrics.recordExecution(outcome, Duration.ofNanos(System.nanoTime() - context.startedNanos()));
    }

    private record ClaimContext(String key, String fingerprint, Duration ttl, long startedNanos) {
    }
}
