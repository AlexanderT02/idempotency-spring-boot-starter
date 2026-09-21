package io.github.alexandert02.idempotency.store;

import java.io.Serializable;
import java.io.Serial;
import static java.util.Objects.nonNull;
import static java.util.Objects.requireNonNull;

/** Stored claim state and its response when completed. */
public record IdempotencyRecord(String fingerprint, State state, CapturedResponse response)
        implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public enum State {
        IN_PROGRESS,
        COMPLETED
    }

    public IdempotencyRecord {
        requireNonNull(fingerprint, "fingerprint");
        requireNonNull(state, "state");
        boolean hasResponse = nonNull(response);
        if ((state == State.COMPLETED) != hasResponse) {
            throw new IllegalArgumentException(
                    "A COMPLETED record must have a response and an IN_PROGRESS record must not; got state="
                            + state + ", response=" + (hasResponse ? "present" : "null"));
        }
    }

    public static IdempotencyRecord inProgress(String fingerprint) {
        return new IdempotencyRecord(fingerprint, State.IN_PROGRESS, null);
    }

    public IdempotencyRecord completedWith(CapturedResponse response) {
        return new IdempotencyRecord(fingerprint, State.COMPLETED, response);
    }

    public boolean isCompleted() {
        return state == State.COMPLETED;
    }
}
