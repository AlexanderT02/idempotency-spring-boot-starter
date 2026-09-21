package io.github.alexandert02.idempotency.store;

import java.io.Serializable;
import java.io.Serial;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static java.util.Objects.isNull;

/** HTTP response stored as raw bytes for exact replay. */
public record CapturedResponse(int status, Map<String, List<String>> headers, byte[] body) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public CapturedResponse {
        if (isNull(headers)) {
            headers = Map.of();
        } else {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
            headers = Collections.unmodifiableMap(copy);
        }
        body = isNull(body) ? new byte[0] : body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
