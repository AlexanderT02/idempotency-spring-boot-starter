package dev.idem.idempotency.store.support;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import static java.util.Objects.isNull;
import java.util.concurrent.ConcurrentHashMap;

import dev.idem.idempotency.store.CapturedResponse;
import dev.idem.idempotency.store.IdempotencyRecord;
import dev.idem.idempotency.store.IdempotencyRecord.State;
import dev.idem.idempotency.store.IdempotencyStore;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class AbstractIdempotencyStoreTest {

    static class MapObjectStore extends ObjectIdempotencyStore {
        final Map<String, IdempotencyRecord> map = new ConcurrentHashMap<>();

        @Override protected boolean putIfAbsent(String k, IdempotencyRecord v, Duration ttl) {
            return isNull(map.putIfAbsent(k, v));
        }
        @Override protected IdempotencyRecord read(String k) { return map.get(k); }
        @Override protected void put(String k, IdempotencyRecord v, Duration ttl) { map.put(k, v); }
        @Override protected void delete(String k) { map.remove(k); }
    }

    static class MapBinaryStore extends BinaryIdempotencyStore {
        final Map<String, byte[]> map = new ConcurrentHashMap<>();

        @Override protected boolean putIfAbsent(String k, byte[] v, Duration ttl) {
            return isNull(map.putIfAbsent(k, v));
        }
        @Override protected byte[] read(String k) { return map.get(k); }
        @Override protected void put(String k, byte[] v, Duration ttl) { map.put(k, v); }
        @Override protected void delete(String k) { map.remove(k); }
    }

    @Test
    void objectStoreFlowWorksThroughTheBaseClass() {
        assertClaimReplayReleaseFlow(new MapObjectStore());
    }

    @Test
    void binaryStoreFlowWorksThroughTheBaseClass() {
        assertClaimReplayReleaseFlow(new MapBinaryStore());
    }

    @Test
    void retriesWhenAKeyDisappearsBetweenClaimAndRead() {
        class RacingStore extends MapObjectStore {
            int attempts;

            @Override
            protected boolean putIfAbsent(String key, IdempotencyRecord value, Duration ttl) {
                return ++attempts > 1;
            }
        }
        var store = new RacingStore();

        assertThat(store.tryClaim("k", "fp", Duration.ofMinutes(1)).acquired()).isTrue();
        assertThat(store.attempts).isEqualTo(2);
    }

    @Test
    void failsClearlyWhenBackendNeverReturnsTheReportedValue() {
        class InconsistentStore extends MapObjectStore {
            int attempts;

            @Override
            protected boolean putIfAbsent(String key, IdempotencyRecord value, Duration ttl) {
                attempts++;
                return false;
            }
        }
        var store = new InconsistentStore();

        assertThatIllegalStateException().isThrownBy(() ->
                store.tryClaim("k", "fp", Duration.ofMinutes(1)))
                .withMessageContaining("returned no value");
        assertThat(store.attempts).isEqualTo(3);
    }

    private void assertClaimReplayReleaseFlow(IdempotencyStore store) {
        assertThat(store.tryClaim("k", "fp", Duration.ofSeconds(60)).acquired()).isTrue();

        var second = store.tryClaim("k", "fp", Duration.ofSeconds(60));
        assertThat(second.acquired()).isFalse();
        assertThat(second.existing().state()).isEqualTo(State.IN_PROGRESS);

        store.complete("k", IdempotencyRecord.inProgress("fp")
                        .completedWith(new CapturedResponse(
                                201, Map.of("Content-Type", java.util.List.of("application/json")),
                                "{\"id\":1}".getBytes(StandardCharsets.UTF_8))),
                Duration.ofSeconds(60));
        IdempotencyRecord loaded = store.get("k").orElseThrow();
        assertThat(loaded.isCompleted()).isTrue();
        assertThat(new String(loaded.response().body(), StandardCharsets.UTF_8)).isEqualTo("{\"id\":1}");

        store.release("k");
        assertThat(store.get("k")).isEmpty();
        assertThat(store.tryClaim("k", "fp", Duration.ofSeconds(60)).acquired()).isTrue();
    }
}
