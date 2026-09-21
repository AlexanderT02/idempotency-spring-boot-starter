package io.github.alexandert02.idempotency.integration;

import io.github.alexandert02.idempotency.store.IdempotencyStore;
import io.github.alexandert02.idempotency.store.testing.IdempotencyStoreContractTest;

class TestIdempotencyStoreContractTest extends IdempotencyStoreContractTest {

    private final IdempotencyStore store = new TestIdempotencyStore();

    @Override
    protected IdempotencyStore store() {
        return store;
    }
}
