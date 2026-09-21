package io.github.alexandert02.idempotency.integration;

import java.util.concurrent.atomic.AtomicInteger;

import io.github.alexandert02.idempotency.Idempotent;
import io.github.alexandert02.idempotency.IdempotencyKeyResolver;
import io.github.alexandert02.idempotency.store.IdempotencyStore;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

@SpringBootConfiguration
@EnableAutoConfiguration
public class TestApp {

    @RestController
    public static class TestController {

        final AtomicInteger paymentExecutions = new AtomicInteger();
        final AtomicInteger slowExecutions = new AtomicInteger();
        final AtomicInteger clientErrorExecutions = new AtomicInteger();
        final AtomicInteger serverErrorExecutions = new AtomicInteger();
        final AtomicInteger largeResponseExecutions = new AtomicInteger();
        final AtomicInteger asyncExecutions = new AtomicInteger();

        @PostMapping("/payments")
        @Idempotent
        public ResponseEntity<String> pay(@RequestBody(required = false) String body) {
            int n = paymentExecutions.incrementAndGet();
            return ResponseEntity.status(201).body("exec-" + n);
        }

        @PostMapping("/slow")
        @Idempotent
        public ResponseEntity<String> slow(@RequestBody(required = false) String body) throws InterruptedException {
            Thread.sleep(400);
            int n = slowExecutions.incrementAndGet();
            return ResponseEntity.ok("slow-" + n);
        }

        @PostMapping("/client-error")
        @Idempotent
        public ResponseEntity<String> clientError(@RequestBody(required = false) String body) {
            clientErrorExecutions.incrementAndGet();
            return ResponseEntity.badRequest().body("bad");
        }

        @PostMapping("/server-error")
        @Idempotent
        public ResponseEntity<String> serverError(@RequestBody(required = false) String body) {
            serverErrorExecutions.incrementAndGet();
            return ResponseEntity.status(500).body("boom");
        }

        @PostMapping("/cookies")
        @Idempotent
        public ResponseEntity<String> cookies(@RequestBody(required = false) String body) {
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.SET_COOKIE, "first=one");
            headers.add(HttpHeaders.SET_COOKIE, "second=two");
            return new ResponseEntity<>("cookies", headers, 200);
        }

        @PostMapping("/large-response")
        @Idempotent
        public ResponseEntity<String> largeResponse(@RequestBody(required = false) String body) {
            int execution = largeResponseExecutions.incrementAndGet();
            return ResponseEntity.ok("x".repeat(64) + execution);
        }

        @PostMapping("/async")
        @Idempotent
        public DeferredResult<String> async() {
            asyncExecutions.incrementAndGet();
            return new DeferredResult<>();
        }

    }

    @Bean
    IdempotencyKeyResolver idempotencyKeyResolver() {
        return (request, handler) -> request.getHeader("Idempotency-Key");
    }

    @Bean
    IdempotencyStore idempotencyStore() {
        return new TestIdempotencyStore();
    }

    @Bean
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
