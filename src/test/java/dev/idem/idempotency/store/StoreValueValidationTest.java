package dev.idem.idempotency.store;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class StoreValueValidationTest {

    @Test
    void claimResultRequiresExactlyOneValidOutcome() {
        IdempotencyRecord existing = IdempotencyRecord.inProgress("fp");

        assertThatIllegalArgumentException().isThrownBy(() -> new ClaimResult(true, existing));
        assertThatIllegalArgumentException().isThrownBy(() -> new ClaimResult(false, null));
    }

    @Test
    void recordRequiresFingerprintAndState() {
        assertThatNullPointerException().isThrownBy(() ->
                new IdempotencyRecord(null, IdempotencyRecord.State.IN_PROGRESS, null));
        assertThatNullPointerException().isThrownBy(() ->
                new IdempotencyRecord("fp", null, null));
    }

    @Test
    void recordStateMustMatchResponsePresence() {
        CapturedResponse response = new CapturedResponse(200, Map.of("X-Test", List.of("value")), null);

        assertThatIllegalArgumentException().isThrownBy(() ->
                new IdempotencyRecord("fp", IdempotencyRecord.State.IN_PROGRESS, response));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new IdempotencyRecord("fp", IdempotencyRecord.State.COMPLETED, null));
    }
}
