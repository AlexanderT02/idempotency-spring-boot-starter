package io.github.alexandert02.idempotency.store.support;

import io.github.alexandert02.idempotency.store.IdempotencyRecord;

/** Base for backends that store {@link IdempotencyRecord} objects directly. */
public abstract class ObjectIdempotencyStore extends AbstractIdempotencyStore<IdempotencyRecord> {

    protected ObjectIdempotencyStore() {
        super(record -> record, value -> value);
    }
}
