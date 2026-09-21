package dev.idem.idempotency.internal.web;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedResponseWrapperTest {

    @Test
    void capturesBodyWithinLimitAndWritesThrough() throws Exception {
        var response = new MockHttpServletResponse();
        var wrapper = new BoundedResponseWrapper(response, 5);

        wrapper.getOutputStream().write("hello".getBytes(StandardCharsets.UTF_8));

        assertThat(wrapper.limitExceeded()).isFalse();
        assertThat(wrapper.capturedBody()).asString(StandardCharsets.UTF_8).isEqualTo("hello");
        assertThat(response.getContentAsString()).isEqualTo("hello");
    }

    @Test
    void stopsCapturingAboveLimitButWritesFullBodyThrough() throws Exception {
        var response = new MockHttpServletResponse();
        var wrapper = new BoundedResponseWrapper(response, 4);

        wrapper.getOutputStream().write("hello".getBytes(StandardCharsets.UTF_8));

        assertThat(wrapper.limitExceeded()).isTrue();
        assertThat(wrapper.capturedBody()).isEmpty();
        assertThat(response.getContentAsString()).isEqualTo("hello");
    }
}
