package io.github.alexandert02.idempotency.internal.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;

/** Writes small RFC 9457-style JSON error responses. */
public final class ProblemResponseWriter {

    private ProblemResponseWriter() {
    }

    public static void write(HttpServletResponse response, int status, String title, String detail) throws IOException {
        response.resetBuffer();
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + escape(title)
                + "\",\"status\":" + status + ",\"detail\":\"" + escape(detail) + "\"}");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
