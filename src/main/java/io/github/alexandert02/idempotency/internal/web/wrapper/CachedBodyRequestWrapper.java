package io.github.alexandert02.idempotency.internal.web.wrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serial;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import static java.util.Objects.nonNull;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/** Request wrapper whose body can be read more than once. */
public final class CachedBodyRequestWrapper extends HttpServletRequestWrapper {

    private final byte[] body;

    public CachedBodyRequestWrapper(HttpServletRequest request, int maxBodySize) throws IOException {
        super(request);
        this.body = readBody(request, maxBodySize);
    }

    private static byte[] readBody(HttpServletRequest request, int maxBodySize) throws IOException {
        try (var input = request.getInputStream();
             var output = new ByteArrayOutputStream(Math.min(maxBodySize, 8192))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() > maxBodySize - read) {
                    throw new BodyTooLargeException();
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    public byte[] getBody() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream buffer = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return buffer.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                throw new UnsupportedOperationException("Async reads are not supported");
            }

            @Override
            public int read() {
                return buffer.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        String enc = getCharacterEncoding();
        Charset charset = nonNull(enc) ? Charset.forName(enc) : StandardCharsets.UTF_8;
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
    }

    public static final class BodyTooLargeException extends IOException {
        @Serial
        private static final long serialVersionUID = 1L;
    }
}
