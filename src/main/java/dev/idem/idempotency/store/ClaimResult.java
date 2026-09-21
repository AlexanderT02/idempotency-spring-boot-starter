package dev.idem.idempotency.store;

import static java.util.Objects.nonNull;
import static java.util.Objects.requireNonNull;

/** Result of an atomic claim. {@code existing} is set only when not acquired. */
public record ClaimResult(boolean acquired, IdempotencyRecord existing) {

    public static final ClaimResult ACQUIRED = new ClaimResult(true, null);

    public ClaimResult {
        if (acquired == nonNull(existing)) {
            throw new IllegalArgumentException(
                    "An acquired claim must have no existing record; a rejected claim must have one");
        }
    }

    public static ClaimResult exists(IdempotencyRecord existing) {
        return new ClaimResult(false, requireNonNull(existing, "existing"));
    }
}
