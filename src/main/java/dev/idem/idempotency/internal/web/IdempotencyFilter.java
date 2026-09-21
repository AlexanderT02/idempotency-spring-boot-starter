package dev.idem.idempotency.internal.web;

import java.io.IOException;
import java.lang.reflect.Method;
import java.security.Principal;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import dev.idem.idempotency.Idempotent;
import dev.idem.idempotency.internal.metrics.IdempotencyMetrics;

import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.WebAsyncTask;
import org.springframework.web.util.ServletRequestPathUtils;

/** Captures bounded request and response bodies for the interceptor. */
public final class IdempotencyFilter extends OncePerRequestFilter implements Ordered {

    private static final int ORDER = 0;
    static final String ATTR_KEY = IdempotencyFilter.class.getName() + ".KEY";

    private final RequestMappingHandlerMapping handlerMapping;
    private final IdempotencyKeyResolverSelector keyResolvers;
    private final int maxRequestBodySize;
    private final int maxResponseBodySize;
    private final IdempotencyMetrics metrics;

    public IdempotencyFilter(RequestMappingHandlerMapping handlerMapping,
                             IdempotencyKeyResolverSelector keyResolvers,
                             int maxRequestBodySize, int maxResponseBodySize,
                             IdempotencyMetrics metrics) {
        if (maxRequestBodySize <= 0 || maxResponseBodySize <= 0) {
            throw new IllegalArgumentException("Body size limits must be positive");
        }
        this.handlerMapping = handlerMapping;
        this.keyResolvers = keyResolvers;
        this.maxRequestBodySize = maxRequestBodySize;
        this.maxResponseBodySize = maxResponseBodySize;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        HandlerMethod handler = resolveHandler(request);
        Idempotent annotation = isNull(handler) ? null : handler.getMethodAnnotation(Idempotent.class);
        if (isNull(annotation)) {
            chain.doFilter(request, response);
            return;
        }
        if (hasUnsupportedReturnType(handler.getMethod())) {
            rejectAsyncEndpoint(response);
            return;
        }
        if (request.getContentLengthLong() > maxRequestBodySize) {
            rejectLargeBody(response);
            return;
        }

        CachedBodyRequestWrapper wrappedRequest;
        try {
            wrappedRequest = (request instanceof CachedBodyRequestWrapper r)
                    ? r : new CachedBodyRequestWrapper(request, maxRequestBodySize);
        } catch (CachedBodyRequestWrapper.BodyTooLargeException exception) {
            rejectLargeBody(response);
            return;
        }

        String resolvedKey = keyResolvers.select(annotation.keyResolver()).resolveKey(wrappedRequest, handler);
        if (!StringUtils.hasText(resolvedKey)) {
            chain.doFilter(wrappedRequest, response);
            return;
        }
        wrappedRequest.setAttribute(ATTR_KEY, storeKey(wrappedRequest, handler, resolvedKey));

        BoundedResponseWrapper wrappedResponse = response instanceof BoundedResponseWrapper r
                ? r : new BoundedResponseWrapper(response, maxResponseBodySize);
        try {
            chain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            wrappedResponse.flushPendingWriter();
        }
    }

    private HandlerMethod resolveHandler(HttpServletRequest request) throws ServletException {
        try {
            if (handlerMapping.usesPathPatterns() && !ServletRequestPathUtils.hasParsedRequestPath(request)) {
                ServletRequestPathUtils.parseAndCache(request);
            }
            HandlerExecutionChain chain = handlerMapping.getHandler(request);
            return nonNull(chain) && chain.getHandler() instanceof HandlerMethod method ? method : null;
        } catch (Exception exception) {
            throw new ServletException("Failed to resolve the request handler", exception);
        }
    }

    private void rejectLargeBody(HttpServletResponse response) throws IOException {
        metrics.recordEvent("body_too_large");
        ProblemResponseWriter.write(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                "Content Too Large", "The request body exceeds the configured idempotency limit");
    }

    private void rejectAsyncEndpoint(HttpServletResponse response) throws IOException {
        metrics.recordEvent("unsupported_async");
        ProblemResponseWriter.write(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Unsupported Idempotent Endpoint",
                "@Idempotent supports synchronous Spring MVC endpoints only");
    }

    private boolean hasUnsupportedReturnType(Method method) {
        return containsUnsupportedType(ResolvableType.forMethodReturnType(method));
    }

    private boolean containsUnsupportedType(ResolvableType type) {
        Class<?> rawType = type.resolve();
        if (nonNull(rawType) && (Callable.class.isAssignableFrom(rawType)
                || Future.class.isAssignableFrom(rawType)
                || CompletionStage.class.isAssignableFrom(rawType)
                || DeferredResult.class.isAssignableFrom(rawType)
                || WebAsyncTask.class.isAssignableFrom(rawType)
                || ResponseBodyEmitter.class.isAssignableFrom(rawType)
                || StreamingResponseBody.class.isAssignableFrom(rawType))) {
            return true;
        }
        for (ResolvableType generic : type.getGenerics()) {
            if (containsUnsupportedType(generic)) {
                return true;
            }
        }
        return false;
    }

    private String storeKey(HttpServletRequest request, HandlerMethod handler, String resolvedKey) {
        Principal principal = request.getUserPrincipal();
        String principalScope = isNull(principal) ? "anonymous" : principal.getName();
        String operation = handler.getBeanType().getName() + "#" + handler.getMethod().toGenericString();
        return Sha256Digest.hash(
                Sha256Digest.utf8("idempotency-key-v1"),
                Sha256Digest.utf8(operation),
                Sha256Digest.utf8(principalScope),
                Sha256Digest.utf8(resolvedKey));
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
