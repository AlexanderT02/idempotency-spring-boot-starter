package dev.idem.idempotency.store;

import java.time.Duration;
import java.util.Optional;

/** Persistence contract for idempotency records. Claims must be atomic. */
public interface IdempotencyStore {

    /** Atomically claims a key or returns its existing record. */
    ClaimResult tryClaim(String key, String fingerprint, Duration ttl);

    Optional<IdempotencyRecord> get(String key);

    void complete(String key, IdempotencyRecord record, Duration ttl);

    /** Removes a claim so the key can be retried. */
    void release(String key);
}
