package io.github.alexandert02.idempotency.internal.web;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Creates unambiguous SHA-256 hashes from multiple values. */
public final class Sha256Digest {

    private Sha256Digest() {
    }

    public static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public static String hash(byte[]... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] value : values) {
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
                digest.update(value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }
}
