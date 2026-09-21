package io.github.alexandert02.idempotency.internal.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

/** Captures a bounded response body while writing through to the client. */
final class BoundedResponseWrapper extends HttpServletResponseWrapper {

    private final int maxBodySize;
    private final ByteArrayOutputStream captured;
    private ServletOutputStream outputStream;
    private PrintWriter writer;
    private boolean limitExceeded;

    BoundedResponseWrapper(HttpServletResponse response, int maxBodySize) {
        super(response);
        if (maxBodySize <= 0) {
            throw new IllegalArgumentException("maxBodySize must be positive");
        }
        this.maxBodySize = maxBodySize;
        this.captured = new ByteArrayOutputStream(Math.min(maxBodySize, 8192));
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (nonNull(writer)) {
            throw new IllegalStateException("getWriter() has already been called");
        }
        if (isNull(outputStream)) {
            outputStream = capturingStream();
        }
        return outputStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (nonNull(outputStream)) {
            throw new IllegalStateException("getOutputStream() has already been called");
        }
        if (isNull(writer)) {
            Charset charset = Charset.forName(getCharacterEncoding());
            writer = new PrintWriter(new OutputStreamWriter(capturingStream(), charset));
        }
        return writer;
    }

    byte[] capturedBody() {
        flushWriter();
        return captured.toByteArray();
    }

    boolean limitExceeded() {
        flushWriter();
        return limitExceeded;
    }

    void flushPendingWriter() {
        flushWriter();
    }

    @Override
    public void resetBuffer() {
        flushWriter();
        super.resetBuffer();
        captured.reset();
        limitExceeded = false;
    }

    @Override
    public void reset() {
        flushWriter();
        super.reset();
        captured.reset();
        limitExceeded = false;
    }

    private ServletOutputStream capturingStream() throws IOException {
        ServletOutputStream delegate = getResponse().getOutputStream();
        return new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return delegate.isReady();
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
                delegate.setWriteListener(writeListener);
            }

            @Override
            public void write(int value) throws IOException {
                delegate.write(value);
                capture(new byte[]{(byte) value}, 0, 1);
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                delegate.write(bytes, offset, length);
                capture(bytes, offset, length);
            }

            @Override
            public void flush() throws IOException {
                delegate.flush();
            }

            @Override
            public void close() throws IOException {
                delegate.close();
            }
        };
    }

    private void capture(byte[] bytes, int offset, int length) {
        if (limitExceeded) {
            return;
        }
        if (captured.size() > maxBodySize - length) {
            captured.reset();
            limitExceeded = true;
            return;
        }
        captured.write(bytes, offset, length);
    }

    private void flushWriter() {
        if (nonNull(writer)) {
            writer.flush();
        }
    }
}
