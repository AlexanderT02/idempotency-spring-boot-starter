package dev.idem.idempotency.store.support;

import dev.idem.idempotency.store.IdempotencyRecord;

/** Base for backends that store {@link IdempotencyRecord} objects directly. */
public abstract class ObjectIdempotencyStore extends AbstractIdempotencyStore<IdempotencyRecord> {

    protected ObjectIdempotencyStore() {
        super(record -> record, value -> value);
    }
}
