package dev.idem.idempotency.integration;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import dev.idem.idempotency.Idempotent;
import dev.idem.idempotency.IdempotencyKeyResolver;
import dev.idem.idempotency.store.IdempotencyStore;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = CustomKeyResolverIntegrationTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "idempotency.ttl=24h")
class CustomKeyResolverIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    App.OrderController controller;

    private final RestTemplate rest = new RestTemplate();

    private void postOrder(String orderId) {
        rest.exchange("http://localhost:" + port + "/orders/" + orderId, HttpMethod.POST,
                new HttpEntity<>("{}", new HttpHeaders()), String.class);
    }

    @Test
    void customResolverDerivesKeyFromRequest() {
        postOrder("A");
        postOrder("A");
        assertThat(controller.executions.get()).isEqualTo(1);

        postOrder("B");
        assertThat(controller.executions.get()).isEqualTo(2);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        @Bean
        @Primary
        IdempotencyKeyResolver defaultKeyResolver() {
            return (request, handler) -> null;
        }

        @Bean
        @Qualifier("path")
        IdempotencyKeyResolver pathKeyResolver() {
            return (request, handler) -> {
                if (!handler.getMethod().getName().equals("create")) {
                    return null;
                }
                @SuppressWarnings("unchecked")
                Map<String, String> variables = (Map<String, String>) request.getAttribute(
                        HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
                return variables.get("orderId");
            };
        }

        @Bean
        IdempotencyStore idempotencyStore() {
            return new TestIdempotencyStore();
        }

        @RestController
        static class OrderController {
            final AtomicInteger executions = new AtomicInteger();

            @PostMapping("/orders/{orderId}")
            @Idempotent(keyResolver = "path")
            public String create(@PathVariable String orderId) {
                return "order-" + executions.incrementAndGet();
            }
        }
    }
}
