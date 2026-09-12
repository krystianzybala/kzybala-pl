package pl.kzybala.lab.binser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;

/**
 * Variant: generic object codec (JDK {@code ObjectOutputStream}/{@code
 * ObjectInputStream}). No schema is declared anywhere; the class descriptor,
 * field names and reflection-based field walk travel on every single
 * message. This is the baseline the fixed-layout variants are measured
 * against — it is not "Java's serialization story," only the specific,
 * deliberately naive generic path.
 */
public final class GenericObjectCodec {

    public byte[] encode(EventRecord event) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(SerializableEvent.fromRecord(event));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    public EventRecord decode(byte[] wire) {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(wire))) {
            SerializableEvent e = (SerializableEvent) ois.readObject();
            return e.toRecord();
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("generic decode failed", e);
        }
    }
}
