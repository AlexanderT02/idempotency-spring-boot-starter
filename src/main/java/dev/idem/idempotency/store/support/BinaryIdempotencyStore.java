package dev.idem.idempotency.store.support;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import dev.idem.idempotency.store.IdempotencyRecord;

/** Base for backends that store Java-serialized {@code byte[]} values. */
public abstract class BinaryIdempotencyStore extends AbstractIdempotencyStore<byte[]> {

    protected BinaryIdempotencyStore() {
        super(BinaryIdempotencyStore::serialize, BinaryIdempotencyStore::deserialize);
    }

    private static byte[] serialize(IdempotencyRecord record) {
        try (var output = new ByteArrayOutputStream();
             var objects = new ObjectOutputStream(output)) {
            objects.writeObject(record);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize idempotency record", exception);
        }
    }

    private static IdempotencyRecord deserialize(byte[] value) {
        try (var objects = new ObjectInputStream(new ByteArrayInputStream(value))) {
            return (IdempotencyRecord) objects.readObject();
        } catch (IOException | ClassNotFoundException | ClassCastException exception) {
            throw new IllegalStateException("Failed to deserialize idempotency record", exception);
        }
    }
}
