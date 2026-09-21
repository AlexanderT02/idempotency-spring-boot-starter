package dev.idem.idempotency.integration;

import dev.idem.idempotency.store.IdempotencyStore;
import dev.idem.idempotency.store.testing.IdempotencyStoreContractTest;

class TestIdempotencyStoreContractTest extends IdempotencyStoreContractTest {

    private final IdempotencyStore store = new TestIdempotencyStore();

    @Override
    protected IdempotencyStore store() {
        return store;
    }
}
