package dev.idem.idempotency.internal.metrics;

import java.time.Duration;

/** Internal metrics bridge. */
public interface IdempotencyMetrics {

    IdempotencyMetrics NOOP = new IdempotencyMetrics() {
        @Override
        public void recordEvent(String event) {
        }

        @Override
        public void recordStoreError(String operation) {
        }

        @Override
        public void recordExecution(String outcome, Duration duration) {
        }
    };

    void recordEvent(String event);

    void recordStoreError(String operation);

    void recordExecution(String outcome, Duration duration);
}
