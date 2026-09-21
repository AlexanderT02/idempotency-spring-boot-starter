package io.github.alexandert02.idempotency.integration;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import static java.util.Objects.isNull;

import io.github.alexandert02.idempotency.store.IdempotencyRecord;
import io.github.alexandert02.idempotency.store.support.ObjectIdempotencyStore;

final class TestIdempotencyStore extends ObjectIdempotencyStore {

    private final ConcurrentHashMap<String, IdempotencyRecord> records = new ConcurrentHashMap<>();

    @Override
    protected boolean putIfAbsent(String key, IdempotencyRecord value, Duration ttl) {
        return isNull(records.putIfAbsent(key, value));
    }

    @Override
    protected IdempotencyRecord read(String key) {
        return records.get(key);
    }

    @Override
    protected void put(String key, IdempotencyRecord value, Duration ttl) {
        records.put(key, value);
    }

    @Override
    protected void delete(String key) {
        records.remove(key);
    }
}
