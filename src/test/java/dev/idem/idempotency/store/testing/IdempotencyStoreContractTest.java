package dev.idem.idempotency.store.testing;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import dev.idem.idempotency.store.ClaimResult;
import dev.idem.idempotency.store.IdempotencyRecord.State;
import dev.idem.idempotency.store.IdempotencyStore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reusable concurrency contract for {@link IdempotencyStore} implementations. */
public abstract class IdempotencyStoreContractTest {

    private static final int CONCURRENT_CLAIMS = 16;

    /** Returns the store instance to verify. */
    protected abstract IdempotencyStore store();

    @Test
    void concurrentClaimsHaveExactlyOneWinner() throws Exception {
        IdempotencyStore store = store();
        String key = "atomicity-" + UUID.randomUUID();
        String fingerprint = "same-request";
        CountDownLatch ready = new CountDownLatch(CONCURRENT_CLAIMS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_CLAIMS);

        try {
            List<Future<ClaimResult>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_CLAIMS; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS), "Timed out waiting to start");
                    return store.tryClaim(key, fingerprint, Duration.ofMinutes(1));
                }));
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS), "Workers did not become ready");
            start.countDown();

            List<ClaimResult> results = new ArrayList<>();
            for (Future<ClaimResult> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }

            assertEquals(1, results.stream().filter(ClaimResult::acquired).count(),
                    "tryClaim must allow exactly one caller to acquire the key");
            results.stream().filter(result -> !result.acquired()).forEach(result -> {
                assertNotNull(result.existing());
                assertFalse(result.existing().isCompleted());
                assertEquals(State.IN_PROGRESS, result.existing().state());
            });
        } finally {
            start.countDown();
            executor.shutdownNow();
            store.release(key);
        }
    }
}
