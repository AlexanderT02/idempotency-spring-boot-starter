package io.github.alexandert02.idempotency.internal.web;

import java.util.concurrent.atomic.AtomicBoolean;
import static java.util.Objects.isNull;

import io.github.alexandert02.idempotency.Idempotent;
import io.github.alexandert02.idempotency.IdempotencyKeyResolver;
import io.github.alexandert02.idempotency.internal.metrics.IdempotencyMetrics;
import io.github.alexandert02.idempotency.internal.web.resolver.IdempotencyKeyResolverSelector;
import io.github.alexandert02.idempotency.internal.web.wrapper.BoundedResponseWrapper;
import io.github.alexandert02.idempotency.internal.web.wrapper.CachedBodyRequestWrapper;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.context.request.async.DeferredResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdempotencyFilterTest {

    @Test
    void unmatchedRequestPassesThroughWithoutWrappers() throws Exception {
        var filter = new IdempotencyFilter(handlerMapping(null), selector((request, handler) -> "key"),
                1024, 1024, IdempotencyMetrics.NOOP);
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        assertThatNoException().isThrownBy(() -> filter.doFilter(request, response, (actualRequest, actualResponse) -> {
            assertThat(actualRequest).isSameAs(request);
            assertThat(actualResponse).isSameAs(response);
        }));
    }

    @Test
    void oversizedMatchingRequestReturnsPayloadTooLarge() throws Exception {
        HandlerMethod handler = new HandlerMethod(new TestController(),
                TestController.class.getDeclaredMethod("create"));
        var filter = new IdempotencyFilter(handlerMapping(handler), selector((request, method) -> "key"),
                4, 1024, IdempotencyMetrics.NOOP);
        var request = new MockHttpServletRequest();
        request.setContent(new byte[5]);
        var response = new MockHttpServletResponse();
        var invoked = new AtomicBoolean();

        filter.doFilter(request, response, (actualRequest, actualResponse) -> invoked.set(true));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"status\":413");
    }

    @Test
    void storeKeyIsHashedAndScopedByPrincipal() throws Exception {
        HandlerMethod handler = new HandlerMethod(new TestController(),
                TestController.class.getDeclaredMethod("create"));
        var filter = new IdempotencyFilter(handlerMapping(handler),
                selector((request, method) -> "sensitive-client-key"),
                1024, 1024, IdempotencyMetrics.NOOP);

        String aliceKey = resolvedStoreKey(filter, "alice");
        String bobKey = resolvedStoreKey(filter, "bob");

        assertThat(aliceKey).hasSize(64).doesNotContain("sensitive-client-key");
        assertThat(resolvedStoreKey(filter, "alice")).isEqualTo(aliceKey);
        assertThat(bobKey).hasSize(64).isNotEqualTo(aliceKey);
        assertThat(filter.getOrder()).isGreaterThan(-100);
    }

    @Test
    void asyncReturnTypeIsRejected() throws Exception {
        HandlerMethod handler = new HandlerMethod(new TestController(),
                TestController.class.getDeclaredMethod("async"));
        var filter = new IdempotencyFilter(handlerMapping(handler), selector((request, method) -> "key"),
                1024, 1024, IdempotencyMetrics.NOOP);
        var response = new MockHttpServletResponse();
        var invoked = new AtomicBoolean();

        filter.doFilter(new MockHttpServletRequest(), response,
                (actualRequest, actualResponse) -> invoked.set(true));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).contains("synchronous Spring MVC endpoints only");
    }

    private String resolvedStoreKey(IdempotencyFilter filter, String principal) throws Exception {
        var request = new MockHttpServletRequest();
        request.setUserPrincipal(() -> principal);
        var response = new MockHttpServletResponse();
        var key = new String[1];
        filter.doFilter(request, response, (actualRequest, actualResponse) ->
                key[0] = (String) actualRequest.getAttribute(IdempotencyFilter.ATTR_KEY));
        return key[0];
    }

    private RequestMappingHandlerMapping handlerMapping(HandlerMethod handler) throws Exception {
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        when(mapping.getHandler(any())).thenReturn(
                isNull(handler) ? null : new HandlerExecutionChain(handler));
        return mapping;
    }

    private IdempotencyKeyResolverSelector selector(IdempotencyKeyResolver resolver) {
        var beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("keyResolver", resolver);
        return new IdempotencyKeyResolverSelector(beanFactory);
    }

    static class TestController {
        @Idempotent
        void create() {
        }

        @Idempotent
        DeferredResult<String> async() {
            return new DeferredResult<>();
        }
    }
}
