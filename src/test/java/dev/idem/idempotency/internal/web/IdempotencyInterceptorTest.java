package dev.idem.idempotency.internal.web;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static java.util.Objects.nonNull;
import java.util.function.Function;

import dev.idem.idempotency.Idempotent;
import dev.idem.idempotency.IdempotencyFingerprintResolver;
import dev.idem.idempotency.autoconfigure.IdempotencyProperties;
import dev.idem.idempotency.internal.metrics.IdempotencyMetrics;
import dev.idem.idempotency.store.CapturedResponse;
import dev.idem.idempotency.store.ClaimResult;
import dev.idem.idempotency.store.IdempotencyRecord;
import dev.idem.idempotency.store.IdempotencyStore;

import org.junit.jupiter.api.Test;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyInterceptorTest {

    private final RecordingStore store = new RecordingStore();
    private final RecordingMetrics metrics = new RecordingMetrics();

    @Test
    void blankKeySkipsIdempotency() throws Exception {
        var interceptor = interceptor(true, false);
        var request = request("POST", "/payments", "{}");
        request.removeAttribute(IdempotencyFilter.ATTR_KEY);

        boolean proceed = interceptor.preHandle(request,
                new MockHttpServletResponse(), handler("standard"));

        assertThat(proceed).isTrue();
        assertThat(store.claims).isEmpty();
        assertThat(metrics.events).isEmpty();
    }

    @Test
    void methodWithoutAnnotationIsIgnored() throws Exception {
        var interceptor = interceptor(true, false);

        boolean proceed = interceptor.preHandle(request("POST", "/plain", "{}"),
                new MockHttpServletResponse(), handler("plain"));

        assertThat(proceed).isTrue();
        assertThat(store.claims).isEmpty();
    }

    @Test
    void acquiredClaimUsesConfiguredTtl() throws Exception {
        var interceptor = interceptor(true, false);

        boolean proceed = interceptor.preHandle(request("POST", "/payments", "{}"),
                new MockHttpServletResponse(), handler("standard"));

        assertThat(proceed).isTrue();
        assertThat(store.claims).singleElement().satisfies(claim -> {
            assertThat(claim.key()).isEqualTo("key");
            assertThat(claim.ttl()).isEqualTo(Duration.ofMinutes(5));
        });
        assertThat(metrics.events).containsExactly("acquired");
    }

    @Test
    void annotationTtlOverridesConfiguration() throws Exception {
        var interceptor = interceptor(true, false);

        interceptor.preHandle(request("POST", "/payments", "{}"),
                new MockHttpServletResponse(), handler("withTtl"));

        assertThat(store.claims.getFirst().ttl()).isEqualTo(Duration.ofSeconds(12));
    }

    @Test
    void negativeAnnotationTtlIsRejected() throws Exception {
        var interceptor = interceptor(true, false);

        assertThatIllegalStateException().isThrownBy(() -> interceptor.preHandle(
                request("POST", "/payments", "{}"), new MockHttpServletResponse(), handler("negativeTtl")));
        assertThat(store.claims).isEmpty();
    }

    @Test
    void fingerprintIncludesMethodConcretePathAndBody() throws Exception {
        var interceptor = interceptor(true, false);

        interceptor.preHandle(request("POST", "/orders/41", "one"),
                new MockHttpServletResponse(), handler("standard"));
        interceptor.preHandle(request("POST", "/orders/42", "one"),
                new MockHttpServletResponse(), handler("standard"));
        interceptor.preHandle(request("POST", "/orders/41", "two"),
                new MockHttpServletResponse(), handler("standard"));
        interceptor.preHandle(request("PUT", "/orders/41", "one"),
                new MockHttpServletResponse(), handler("standard"));

        assertThat(store.claims).extracting(Claim::fingerprint).doesNotHaveDuplicates();
    }

    @Test
    void fingerprintIncludesQueryAndContentType() throws Exception {
        var interceptor = interceptor(true, false);

        interceptor.preHandle(request("POST", "/orders", "one", "currency=EUR", "application/json"),
                new MockHttpServletResponse(), handler("standard"));
        interceptor.preHandle(request("POST", "/orders", "one", "currency=USD", "application/json"),
                new MockHttpServletResponse(), handler("standard"));
        interceptor.preHandle(request("POST", "/orders", "one", "currency=EUR", "text/plain"),
                new MockHttpServletResponse(), handler("standard"));

        assertThat(store.claims).extracting(Claim::fingerprint).doesNotHaveDuplicates();
    }

    @Test
    void customFingerprintResolverIsUsed() throws Exception {
        var properties = new IdempotencyProperties(Duration.ofMinutes(5),
                DataSize.ofMegabytes(1), DataSize.ofMegabytes(2),
                new IdempotencyProperties.Cache(true, false),
                new IdempotencyProperties.Metrics(false));
        IdempotencyFingerprintResolver custom = (request, handler) -> "custom-fingerprint";
        var interceptor = new IdempotencyInterceptor(store, properties, metrics, custom);

        interceptor.preHandle(request("POST", "/payments", "{}"),
                new MockHttpServletResponse(), handler("standard"));

        assertThat(store.claims).singleElement()
                .extracting(Claim::fingerprint)
                .isEqualTo("custom-fingerprint");
    }

    @Test
    void differentFingerprintReturnsProblemAndRecordsConflictMetric() throws Exception {
        store.claimResult = fingerprint -> ClaimResult.exists(IdempotencyRecord.inProgress("other"));
        var interceptor = interceptor(true, false);
        var response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request("POST", "/payments/42", "{}"),
                response, handler("standard"));

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"status\":422", "different request");
        assertThat(metrics.events).containsExactly("fingerprint_mismatch");
    }

    @Test
    void matchingInProgressClaimReturnsConflictAndRetryAfter() throws Exception {
        store.claimResult = fingerprint -> ClaimResult.exists(IdempotencyRecord.inProgress(fingerprint));
        var interceptor = interceptor(true, false);
        var response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request("POST", "/payments", "{}"),
                response, handler("standard"));

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getContentAsString()).contains("\"status\":409");
        assertThat(metrics.events).containsExactly("in_progress");
    }

    @Test
    void completedClaimReplaysStatusBodyAndEveryHeaderValue() throws Exception {
        store.claimResult = fingerprint -> ClaimResult.exists(IdempotencyRecord.inProgress(fingerprint).completedWith(
                new CapturedResponse(201, Map.of(
                        "Content-Type", List.of("application/json"),
                        "Set-Cookie", List.of("first=one", "second=two"),
                        "Content-Length", List.of("999")), "saved".getBytes(StandardCharsets.UTF_8))));
        var interceptor = interceptor(true, false);
        var response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request("POST", "/payments", "{}"),
                response, handler("standard"));

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getContentAsString()).isEqualTo("saved");
        assertThat(response.getHeaders("Set-Cookie")).containsExactly("first=one", "second=two");
        assertThat(response.getHeader("Content-Length")).isNull();
        assertThat(response.getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(metrics.events).containsExactly("replayed");
    }

    @Test
    void successfulResponseIsCompletedWithBodyHeadersAndTtl() throws Exception {
        var interceptor = interceptor(true, false);
        var request = request("POST", "/payments", "{}");
        var response = new BoundedResponseWrapper(new MockHttpServletResponse(), 1024);
        interceptor.preHandle(request, response, handler("standard"));
        response.setStatus(201);
        response.addHeader("Set-Cookie", "first=one");
        response.addHeader("Set-Cookie", "second=two");
        response.setContentType("application/json");
        response.getOutputStream().write("created".getBytes(StandardCharsets.UTF_8));

        interceptor.afterCompletion(request, response, handler("standard"), null);

        assertThat(store.completedKey).isEqualTo("key");
        assertThat(store.completedTtl).isEqualTo(Duration.ofMinutes(5));
        assertThat(store.completed.response().status()).isEqualTo(201);
        assertThat(store.completed.response().headers().get("Set-Cookie"))
                .containsExactly("first=one", "second=two");
        assertThat(new String(store.completed.response().body(), StandardCharsets.UTF_8)).isEqualTo("created");
        assertThat(metrics.events).containsExactly("acquired", "completed");
        assertThat(metrics.executions).containsExactly("completed");
    }

    @Test
    void uncachedClientErrorReleasesClaim() throws Exception {
        var interceptor = interceptor(false, false);
        var request = request("POST", "/payments", "{}");
        var response = new BoundedResponseWrapper(new MockHttpServletResponse(), 1024);
        interceptor.preHandle(request, response, handler("standard"));
        response.setStatus(400);

        interceptor.afterCompletion(request, response, handler("standard"), null);

        assertThat(store.releasedKeys).containsExactly("key");
        assertThat(store.completed).isNull();
        assertThat(metrics.events).containsExactly("acquired", "released");
    }

    @Test
    void configuredServerErrorIsCached() throws Exception {
        var interceptor = interceptor(true, true);
        var request = request("POST", "/payments", "{}");
        var response = new BoundedResponseWrapper(new MockHttpServletResponse(), 1024);
        interceptor.preHandle(request, response, handler("standard"));
        response.setStatus(500);

        interceptor.afterCompletion(request, response, handler("standard"), null);

        assertThat(store.completed.response().status()).isEqualTo(500);
        assertThat(store.releasedKeys).isEmpty();
    }

    @Test
    void handlerExceptionAlwaysReleasesClaim() throws Exception {
        var interceptor = interceptor(true, true);
        var request = request("POST", "/payments", "{}");
        var response = new BoundedResponseWrapper(new MockHttpServletResponse(), 1024);
        interceptor.preHandle(request, response, handler("standard"));

        interceptor.afterCompletion(request, response, handler("standard"), new RuntimeException("failed"));

        assertThat(store.releasedKeys).containsExactly("key");
        assertThat(store.completed).isNull();
    }

    @Test
    void oversizedResponsePassesThroughAndReleasesClaim() throws Exception {
        var interceptor = interceptor(true, false);
        var request = request("POST", "/payments", "{}");
        var nativeResponse = new MockHttpServletResponse();
        var response = new BoundedResponseWrapper(nativeResponse, 4);
        interceptor.preHandle(request, response, handler("standard"));
        response.getOutputStream().write("created".getBytes(StandardCharsets.UTF_8));

        interceptor.afterCompletion(request, response, handler("standard"), null);

        assertThat(nativeResponse.getContentAsString()).isEqualTo("created");
        assertThat(store.completed).isNull();
        assertThat(store.releasedKeys).containsExactly("key");
        assertThat(metrics.events).containsExactly("acquired", "response_too_large", "released");
    }

    @Test
    void programmaticAsyncHandlingReleasesClaimAndDoesNotReclaim() throws Exception {
        var interceptor = interceptor(true, false);
        var request = request("POST", "/payments", "{}");
        var response = new BoundedResponseWrapper(new MockHttpServletResponse(), 1024);
        HandlerMethod handler = handler("standard");
        interceptor.preHandle(request, response, handler);

        interceptor.afterConcurrentHandlingStarted(request, response, handler);
        boolean proceed = interceptor.preHandle(request, response, handler);

        assertThat(proceed).isTrue();
        assertThat(store.claims).hasSize(1);
        assertThat(store.releasedKeys).containsExactly("key");
        assertThat(metrics.events).containsExactly("acquired", "async_released", "released");
    }

    @Test
    void storeFailuresAreRecordedAndPropagated() throws Exception {
        store.claimFailure = new IllegalStateException("backend down");
        var interceptor = interceptor(true, false);

        assertThatThrownBy(() -> interceptor.preHandle(request("POST", "/payments", "{}"),
                new MockHttpServletResponse(), handler("standard")))
                .isSameAs(store.claimFailure);
        assertThat(metrics.storeErrors).containsExactly("claim");
    }

    @Test
    void completionFailureIsRecordedAndPropagated() throws Exception {
        var interceptor = interceptor(true, false);
        var request = request("POST", "/payments", "{}");
        var response = new BoundedResponseWrapper(new MockHttpServletResponse(), 1024);
        interceptor.preHandle(request, response, handler("standard"));
        store.completeFailure = new IllegalStateException("write failed");

        assertThatThrownBy(() -> interceptor.afterCompletion(request, response, handler("standard"), null))
                .isSameAs(store.completeFailure);
        assertThat(metrics.storeErrors).containsExactly("complete");
        assertThat(metrics.executions).containsExactly("store_error");
    }

    @Test
    void releaseFailureIsRecordedAndPropagated() throws Exception {
        var interceptor = interceptor(false, false);
        var request = request("POST", "/payments", "{}");
        var response = new BoundedResponseWrapper(new MockHttpServletResponse(), 1024);
        interceptor.preHandle(request, response, handler("standard"));
        response.setStatus(400);
        store.releaseFailure = new IllegalStateException("delete failed");

        assertThatThrownBy(() -> interceptor.afterCompletion(request, response, handler("standard"), null))
                .isSameAs(store.releaseFailure);
        assertThat(metrics.storeErrors).containsExactly("release");
        assertThat(metrics.executions).containsExactly("store_error");
    }

    private IdempotencyInterceptor interceptor(boolean cacheClientErrors, boolean cacheServerErrors) {
        var properties = new IdempotencyProperties(Duration.ofMinutes(5),
                DataSize.ofMegabytes(1), DataSize.ofMegabytes(2),
                new IdempotencyProperties.Cache(cacheClientErrors, cacheServerErrors),
                new IdempotencyProperties.Metrics(false));
        return new IdempotencyInterceptor(store, properties, metrics,
                new DefaultIdempotencyFingerprintResolver());
    }

    private CachedBodyRequestWrapper request(String method, String path, String body) throws Exception {
        return request(method, path, body, null, null);
    }

    private CachedBodyRequestWrapper request(String method, String path, String body,
                                             String query, String contentType) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        request.setQueryString(query);
        request.setContentType(contentType);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        var wrapped = new CachedBodyRequestWrapper(request, 1024);
        wrapped.setAttribute(IdempotencyFilter.ATTR_KEY, "key");
        return wrapped;
    }

    private HandlerMethod handler(String name) throws NoSuchMethodException {
        return new HandlerMethod(new TestController(), TestController.class.getDeclaredMethod(name));
    }

    static class TestController {
        @Idempotent void standard() { }
        @Idempotent(ttlSeconds = 12) void withTtl() { }
        @Idempotent(ttlSeconds = -1) void negativeTtl() { }
        void plain() { }
    }

    record Claim(String key, String fingerprint, Duration ttl) { }

    static class RecordingStore implements IdempotencyStore {
        final List<Claim> claims = new ArrayList<>();
        final List<String> releasedKeys = new ArrayList<>();
        Function<String, ClaimResult> claimResult = fingerprint -> ClaimResult.ACQUIRED;
        RuntimeException claimFailure;
        RuntimeException completeFailure;
        RuntimeException releaseFailure;
        String completedKey;
        IdempotencyRecord completed;
        Duration completedTtl;

        @Override
        public ClaimResult tryClaim(String key, String fingerprint, Duration ttl) {
            if (nonNull(claimFailure)) throw claimFailure;
            claims.add(new Claim(key, fingerprint, ttl));
            return claimResult.apply(fingerprint);
        }

        @Override
        public Optional<IdempotencyRecord> get(String key) {
            return Optional.empty();
        }

        @Override
        public void complete(String key, IdempotencyRecord record, Duration ttl) {
            if (nonNull(completeFailure)) throw completeFailure;
            completedKey = key;
            completed = record;
            completedTtl = ttl;
        }

        @Override
        public void release(String key) {
            if (nonNull(releaseFailure)) throw releaseFailure;
            releasedKeys.add(key);
        }
    }

    static class RecordingMetrics implements IdempotencyMetrics {
        final List<String> events = new ArrayList<>();
        final List<String> storeErrors = new ArrayList<>();
        final List<String> executions = new ArrayList<>();

        @Override public void recordEvent(String event) { events.add(event); }
        @Override public void recordStoreError(String operation) { storeErrors.add(operation); }
        @Override public void recordExecution(String outcome, Duration duration) { executions.add(outcome); }
    }
}
