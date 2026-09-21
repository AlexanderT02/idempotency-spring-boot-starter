package dev.idem.idempotency.autoconfigure;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import static java.util.Objects.isNull;

/** Configuration properties. {@code idempotency.ttl} is required. */
@ConfigurationProperties(prefix = "idempotency")
public record IdempotencyProperties(
        Duration ttl,
        @DefaultValue("1MB") DataSize maxRequestBodySize,
        @DefaultValue("2MB") DataSize maxResponseBodySize,
        @DefaultValue Cache cache,
        @DefaultValue Metrics metrics
) {

    public IdempotencyProperties {
        if (isNull(ttl) || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("idempotency.ttl must be configured with a positive duration");
        }
        validateSize("max-request-body-size", maxRequestBodySize);
        validateSize("max-response-body-size", maxResponseBodySize);
    }

    private static void validateSize(String name, DataSize size) {
        long bytes = isNull(size) ? 0 : size.toBytes();
        if (bytes <= 0 || bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "idempotency." + name + " must be between 1 byte and " + Integer.MAX_VALUE + " bytes");
        }
    }

    /** Controls caching of error responses. */
    public record Cache(
            @DefaultValue("true") boolean clientErrors,
            @DefaultValue("false") boolean serverErrors
    ) {
    }

    /** Controls optional Micrometer instrumentation. */
    public record Metrics(@DefaultValue("false") boolean enabled) {
    }
}
