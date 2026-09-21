package io.github.alexandert02.idempotency.autoconfigure;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class IdempotencyPropertiesTest {

    private static final IdempotencyProperties.Cache CACHE =
            new IdempotencyProperties.Cache(true, false);
    private static final IdempotencyProperties.Metrics METRICS =
            new IdempotencyProperties.Metrics(false);
    private static final DataSize MAX_REQUEST_BODY_SIZE = DataSize.ofMegabytes(1);
    private static final DataSize MAX_RESPONSE_BODY_SIZE = DataSize.ofMegabytes(2);

    @Test
    void acceptsPositiveTtl() {
        var properties = new IdempotencyProperties(Duration.ofHours(2),
                MAX_REQUEST_BODY_SIZE, MAX_RESPONSE_BODY_SIZE, CACHE, METRICS);

        assertThat(properties.ttl()).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void rejectsMissingTtl() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new IdempotencyProperties(null,
                        MAX_REQUEST_BODY_SIZE, MAX_RESPONSE_BODY_SIZE, CACHE, METRICS));
    }

    @Test
    void rejectsNonPositiveTtl() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new IdempotencyProperties(Duration.ZERO,
                        MAX_REQUEST_BODY_SIZE, MAX_RESPONSE_BODY_SIZE, CACHE, METRICS));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new IdempotencyProperties(Duration.ofSeconds(-1),
                        MAX_REQUEST_BODY_SIZE, MAX_RESPONSE_BODY_SIZE, CACHE, METRICS));
    }

    @Test
    void rejectsNonPositiveBodySizeLimits() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new IdempotencyProperties(Duration.ofMinutes(1), DataSize.ofBytes(0),
                        MAX_RESPONSE_BODY_SIZE, CACHE, METRICS));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new IdempotencyProperties(Duration.ofMinutes(1), MAX_REQUEST_BODY_SIZE,
                        DataSize.ofBytes(0), CACHE, METRICS));
    }
}
