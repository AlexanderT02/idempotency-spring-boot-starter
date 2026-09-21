package io.github.alexandert02.idempotency.store;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

import static java.util.Objects.isNull;
import static java.util.Objects.requireNonNull;

/**
 * Base for idempotency stores backed by a key-value system.
 *
 * <p>The claim, completion, lookup, and release workflow is implemented here. Subclasses only
 * provide four backend operations. Conversion between {@link IdempotencyRecord} and the native
 * value type is configured once through the constructor. Use {@link ObjectIdempotencyStore} or
 * {@link BinaryIdempotencyStore} to use the built-in conversions.
 *
 * <p>{@link #putIfAbsent} must be atomic. The supplied TTL must be applied by both
 * {@code putIfAbsent} and {@link #put}; completion starts a new TTL period.
 *
 * @param <V> native value type stored by the backend
 */
public abstract class AbstractIdempotencyStore<V> implements IdempotencyStore {

    private static final int MAX_CLAIM_ATTEMPTS = 3;

    private final Function<IdempotencyRecord, V> toStoredValue;
    private final Function<V, IdempotencyRecord> fromStoredValue;

    /**
     * Creates a store using the supplied conversion functions.
     *
     * @param toStoredValue converts a record to the backend value type
     * @param fromStoredValue converts a backend value to a record
     */
    protected AbstractIdempotencyStore(Function<IdempotencyRecord, V> toStoredValue,
                                       Function<V, IdempotencyRecord> fromStoredValue) {
        this.toStoredValue = requireNonNull(toStoredValue, "toStoredValue");
        this.fromStoredValue = requireNonNull(fromStoredValue, "fromStoredValue");
    }

    /**
     * Atomically stores {@code value} only when {@code key} does not exist.
     *
     * @return {@code true} when the value was created, otherwise {@code false}
     */
    protected abstract boolean putIfAbsent(String key, V value, Duration ttl);

    /** Returns the current value, or {@code null} when absent or expired. */
    protected abstract V read(String key);

    /** Stores the value and expires it after {@code ttl}. */
    protected abstract void put(String key, V value, Duration ttl);

    /** Removes the key and its value. */
    protected abstract void delete(String key);

    @Override
    public final ClaimResult tryClaim(String key, String fingerprint, Duration ttl) {
        requireKey(key);
        requireNonNull(fingerprint, "fingerprint");
        requireTtl(ttl);
        V claim = storedValue(IdempotencyRecord.inProgress(fingerprint));
        for (int attempt = 0; attempt < MAX_CLAIM_ATTEMPTS; attempt++) {
            if (putIfAbsent(key, claim, ttl)) {
                return ClaimResult.ACQUIRED;
            }
            Optional<IdempotencyRecord> existing = get(key);
            if (existing.isPresent()) {
                return ClaimResult.exists(existing.orElseThrow());
            }
        }
        throw new IllegalStateException("Backend reported an existing key but returned no value");
    }

    @Override
    public final Optional<IdempotencyRecord> get(String key) {
        requireKey(key);
        V value = read(key);
        return isNull(value) ? Optional.empty() : Optional.of(decodedValue(value));
    }

    @Override
    public final void complete(String key, IdempotencyRecord record, Duration ttl) {
        requireKey(key);
        requireNonNull(record, "record");
        requireTtl(ttl);
        put(key, storedValue(record), ttl);
    }

    @Override
    public final void release(String key) {
        requireKey(key);
        delete(key);
    }

    private V storedValue(IdempotencyRecord record) {
        return requireNonNull(toStoredValue.apply(record), "toStoredValue returned null");
    }

    private IdempotencyRecord decodedValue(V value) {
        return requireNonNull(fromStoredValue.apply(value), "fromStoredValue returned null");
    }

    private static void requireKey(String key) {
        requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
    }

    private static void requireTtl(Duration ttl) {
        requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
    }
}
