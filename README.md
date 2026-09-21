# idempotency-spring-boot-starter

Idempotency for synchronous Spring MVC endpoints on Spring Boot 4 and Java 21.

For the same scoped key, the first request executes the handler. Later requests replay the stored
response or receive a conflict while the first request is running.

## Setup

```xml
<dependency>
    <groupId>io.github.alexandert02</groupId>
    <artifactId>idempotency-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

Every application provides an `IdempotencyKeyResolver`, an `IdempotencyStore`, and a positive
`idempotency.ttl`:

```java
@Bean
IdempotencyKeyResolver idempotencyKeyResolver() {
    return (request, handler) -> request.getHeader("Idempotency-Key");
}
```

Return `null` or a blank value to skip idempotency for a request. The resolver receives the
`HandlerMethod`, request body, path variables, query, and authenticated principal.

The starter hashes the resolver key together with the controller operation and authenticated
principal before storing it. Raw keys are not persisted. For anonymous users or custom tenant
models, include a stable tenant/client scope in the resolver key. Do not share one global header
namespace between unrelated anonymous users.

## Configuration

```yaml
idempotency:
  ttl: 24h
  max-request-body-size: 1MB
  max-response-body-size: 2MB
  cache:
    client-errors: true
    server-errors: false
  metrics:
    enabled: false
```

The TTL must exceed the longest possible handler execution time, including database calls,
downstream requests, retries, timeouts, and temporary system load. Use a generous safety margin.
If a claim expires while its request is still running, idempotency is no longer guaranteed.

`max-request-body-size` rejects larger requests with HTTP 413. A response larger than
`max-response-body-size` is delivered normally but is not cached, so the key can be retried.

The starter supports synchronous MVC endpoints only. `Callable`, `CompletionStage`, `DeferredResult`,
`WebAsyncTask`, `ResponseBodyEmitter`, SSE, and `StreamingResponseBody` endpoints annotated with
`@Idempotent` are rejected with HTTP 500.

## Endpoint options

Use `@Idempotent` with the configured TTL:

```java
@PostMapping("/payments")
@Idempotent
public Payment create(@RequestBody PaymentRequest request) {
    return service.create(request);
}
```

Override the TTL only when the endpoint needs a different value. It must still exceed the
endpoint's maximum execution time:

```java
@Idempotent(ttlSeconds = 300)
```

With multiple key resolvers, one resolver must be the default (`@Primary`). An endpoint can select
another resolver by bean name or qualifier:

```java
@Bean
@Qualifier("orders")
IdempotencyKeyResolver orderKeyResolver() {
    return (request, handler) -> request.getHeader("X-Order-Id");
}

@PostMapping("/orders")
@Idempotent(keyResolver = "orders")
public Order createOrder() { /* ... */ }
```

Without an explicit value, Spring uses the single resolver or the `@Primary` resolver. Multiple
unqualified resolvers without a primary prevent startup. An unknown qualifier fails bean
resolution.

## Behavior

- Claims are created atomically.
- A different fingerprint for the same key returns HTTP 422.
- A matching request while the first is running returns HTTP 409 and `Retry-After: 1`.
- A completed request replays status, body, and response headers with
  `Idempotency-Replayed: true`.
- By default, the fingerprint contains HTTP method, concrete path, raw query string, content type,
  and body.
- Client and server error caching follows `idempotency.cache`.
- Problem responses use `application/problem+json`.

Override the default fingerprint only when the endpoint needs additional request data:

```java
@Bean
IdempotencyFingerprintResolver idempotencyFingerprintResolver() {
    return (request, handler) -> request.getMethod() + "|"
            + request.getRequestURI() + "|"
            + request.getHeader("X-Request-Version");
}
```

The resolver must return a stable, non-null value and should include every input that can change
the handler result. A custom bean replaces the default globally.

## Implementing a store

Choose the base class by the value your backend stores:

| Backend representation | Base class | Typical use |
| --- | --- | --- |
| `IdempotencyRecord` object | `ObjectIdempotencyStore` | MongoDB or object-capable stores |
| `byte[]` | `BinaryIdempotencyStore` | JDBC BLOBs, binary Redis, Memcached |
| Custom value `V` | `AbstractIdempotencyStore<V>` | JSON, BSON, compression, encryption |

Subclasses implement four backend operations:

```java
putIfAbsent(key, value, ttl); // must be atomic
read(key);                    // null when absent or expired
put(key, value, ttl);         // apply the completion TTL
delete(key);
```

`putIfAbsent` must use the backend's native atomic operation. A separate read followed by write is
not safe. Register the store as a Spring bean, for example with `@Component`.

Typical backend operations are:

- Redis: `SET key value NX EX/PX` (Spring Data Redis: `setIfAbsent(key, value, ttl)`).
- JDBC: a primary-key insert, treating a duplicate-key error as a rejected claim; update and
  delete by key. Remove expired rows before inserting or with a cleanup job.
- MongoDB: an atomic `insert` for the claim, `save` for completion, and a TTL index on the
  expiration field. MongoDB TTL cleanup is asynchronous, so `read` should also check expiration.

For MongoDB, use a backend-specific persistence entity and map it to `IdempotencyRecord`; the
starter does not require a database schema.

## Store contract tests

The test JAR contains a reusable concurrency contract:

```xml
<dependency>
    <groupId>io.github.alexandert02</groupId>
    <artifactId>idempotency-spring-boot-starter</artifactId>
    <version>1.0.0</version>
    <type>test-jar</type>
    <classifier>tests</classifier>
    <scope>test</scope>
</dependency>
```

```java
class RedisIdempotencyStoreTest extends IdempotencyStoreContractTest {
    @Autowired IdempotencyStore store;

    @Override
    protected IdempotencyStore store() {
        return store;
    }
}
```

The contract checks concurrent claims. It does not replace tests for the backend's native atomic
operation or expiration behavior.

## Metrics

Metrics are disabled by default. With Micrometer available, enable them with:

```yaml
idempotency.metrics.enabled: true
```

The starter publishes `idempotency.events`, `idempotency.store.errors`, and
`idempotency.execution` with bounded operation and outcome tags.

## Build

```bash
mvn test
```
