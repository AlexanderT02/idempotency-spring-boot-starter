package io.github.alexandert02.idempotency.internal.metrics;

import java.time.Duration;

import io.micrometer.core.instrument.MeterRegistry;

/** Micrometer-backed idempotency metrics. */
public final class MicrometerIdempotencyMetrics implements IdempotencyMetrics {

    private final MeterRegistry registry;

    public MicrometerIdempotencyMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordEvent(String event) {
        registry.counter("idempotency.events", "event", event).increment();
    }

    @Override
    public void recordStoreError(String operation) {
        registry.counter("idempotency.store.errors", "operation", operation).increment();
    }

    @Override
    public void recordExecution(String outcome, Duration duration) {
        registry.timer("idempotency.execution", "outcome", outcome).record(duration);
    }
}
