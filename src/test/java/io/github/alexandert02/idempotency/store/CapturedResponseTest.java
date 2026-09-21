package io.github.alexandert02.idempotency.store;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapturedResponseTest {

    @Test
    void protectsStoredHeadersAndBodyFromExternalMutation() {
        byte[] body = "original".getBytes(StandardCharsets.UTF_8);
        List<String> cookies = new ArrayList<>(List.of("first=one"));
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Set-Cookie", cookies);
        var response = new CapturedResponse(200, headers, body);

        body[0] = 'X';
        cookies.add("second=two");
        headers.clear();
        byte[] returnedBody = response.body();
        returnedBody[0] = 'Y';

        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("original");
        assertThat(response.headers().get("Set-Cookie")).containsExactly("first=one");
        assertThatThrownBy(() -> response.headers().put("Other", List.of("value")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> response.headers().get("Set-Cookie").add("third=three"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
