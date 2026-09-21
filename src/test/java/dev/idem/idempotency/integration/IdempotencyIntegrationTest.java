package dev.idem.idempotency.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import static java.util.Objects.nonNull;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"idempotency.ttl=24h", "idempotency.max-response-body-size=32B",
                "idempotency.metrics.enabled=true"})
class IdempotencyIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestApp.TestController controller;

    @Autowired
    MeterRegistry meterRegistry;

    private final RestTemplate rest = createLenientRestTemplate();

    private static RestTemplate createLenientRestTemplate() {
        RestTemplate template = new RestTemplate();
        template.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
        return template;
    }

    private HttpEntity<String> request(String body, String key) {
        HttpHeaders headers = new HttpHeaders();
        if (nonNull(key)) {
            headers.set("Idempotency-Key", key);
        }
        return new HttpEntity<>(body, headers);
    }

    private ResponseEntity<String> post(String path, String body, String key) {
        return rest.exchange("http://localhost:" + port + path, HttpMethod.POST,
                request(body, key), String.class);
    }

    @Test
    void duplicateReplaysStoredResponseAndRunsOnce() {
        int executionsBefore = controller.paymentExecutions.get();
        ResponseEntity<String> first = post("/payments", "{\"amount\":10}", "key-1");
        ResponseEntity<String> second = post("/payments", "{\"amount\":10}", "key-1");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(second.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(controller.paymentExecutions.get()).isEqualTo(executionsBefore + 1);
    }

    @Test
    void sameKeyDifferentBodyIsUnprocessable() {
        post("/payments", "{\"amount\":10}", "key-2");
        ResponseEntity<String> mismatch = post("/payments", "{\"amount\":999}", "key-2");
        assertThat(mismatch.getStatusCode().value()).isEqualTo(422);
        assertThat(mismatch.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(mismatch.getBody()).contains("\"status\":422");
    }

    @Test
    void sameKeyDifferentQueryIsUnprocessable() {
        post("/payments?currency=EUR", "{}", "key-query");
        ResponseEntity<String> mismatch = post("/payments?currency=USD", "{}", "key-query");

        assertThat(mismatch.getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void missingKeyPassesThroughUnguarded() {
        ResponseEntity<String> r1 = post("/payments", "{}", null);
        ResponseEntity<String> r2 = post("/payments", "{}", null);
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r2.getBody()).isNotEqualTo(r1.getBody());
    }

    @Test
    void clientErrorIsCachedAndNotReExecuted() {
        ResponseEntity<String> r1 = post("/client-error", "{}", "key-4xx");
        ResponseEntity<String> r2 = post("/client-error", "{}", "key-4xx");
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.clientErrorExecutions.get()).isEqualTo(1);
    }

    @Test
    void serverErrorReleasesKeyAndIsRetried() {
        ResponseEntity<String> r1 = post("/server-error", "{}", "key-5xx");
        ResponseEntity<String> r2 = post("/server-error", "{}", "key-5xx");
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(controller.serverErrorExecutions.get()).isEqualTo(2);
    }

    @Test
    void replayPreservesMultipleHeaderValues() {
        post("/cookies", "{}", "key-cookies");
        ResponseEntity<String> replay = post("/cookies", "{}", "key-cookies");

        assertThat(replay.getHeaders().get(HttpHeaders.SET_COOKIE))
                .containsExactly("first=one", "second=two");
    }

    @Test
    void oversizedResponseIsDeliveredButNotCached() {
        ResponseEntity<String> first = post("/large-response", "{}", "key-large-response");
        ResponseEntity<String> second = post("/large-response", "{}", "key-large-response");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).hasSize(65).isNotEqualTo(second.getBody());
        assertThat(controller.largeResponseExecutions.get()).isEqualTo(2);
        assertThat(second.getHeaders().getFirst("Idempotency-Replayed")).isNull();
    }

    @Test
    void asyncEndpointIsRejectedClearly() {
        ResponseEntity<String> response = post("/async", null, "key-async");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).contains("synchronous Spring MVC endpoints only");
        assertThat(controller.asyncExecutions.get()).isZero();
    }

    @Test
    void recordsMetricsWhenEnabled() {
        post("/payments", "{}", "key-metrics");

        assertThat(meterRegistry.get("idempotency.events")
                .tag("event", "acquired").counter().count()).isGreaterThanOrEqualTo(1);
        assertThat(meterRegistry.get("idempotency.execution")
                .tag("outcome", "completed").timer().count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void concurrentDuplicatesRunExactlyOnce() throws Exception {
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Callable<ResponseEntity<String>>> tasks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            tasks.add(() -> post("/slow", "{\"amount\":1}", "key-race"));
        }

        List<Future<ResponseEntity<String>>> futures = pool.invokeAll(tasks);
        pool.shutdown();

        int ok = 0, conflict = 0;
        for (Future<ResponseEntity<String>> f : futures) {
            int status = f.get().getStatusCode().value();
            if (status == HttpStatus.OK.value()) {
                ok++;
            } else if (status == HttpStatus.CONFLICT.value()) {
                conflict++;
            }
        }

        assertThat(controller.slowExecutions.get()).isEqualTo(1);
        assertThat(ok + conflict).isEqualTo(n);
        assertThat(ok).isGreaterThanOrEqualTo(1);
    }
}
