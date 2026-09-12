package pl.kzybala.lab.binser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BinSerOperationsTest {

    @Test
    void genericCodecRoundTripsAllProfiles() {
        GenericObjectCodec codec = new GenericObjectCodec();
        for (EventRecord event : BinSerFixtures.all()) {
            byte[] wire = codec.encode(event);
            EventRecord decoded = codec.decode(wire);
            assertEquals(event, decoded);
        }
    }

    @Test
    void byteBufferCodecRoundTripsAllProfilesAtFixedWireSize() {
        ByteBufferCodec codec = new ByteBufferCodec();
        for (EventRecord event : BinSerFixtures.all()) {
            byte[] wire = codec.encode(event);
            assertEquals(EventRecord.WIRE_SIZE, wire.length);
            EventRecord decoded = codec.decode(wire);
            assertEquals(event, decoded);
        }
    }

    @Test
    void ffmFlyweightCodecRoundTripsAllProfilesAtFixedWireSize() {
        FfmFlyweightCodec codec = new FfmFlyweightCodec();
        for (EventRecord event : BinSerFixtures.all()) {
            byte[] wire = codec.encode(event);
            assertEquals(EventRecord.WIRE_SIZE, wire.length);
            EventView view = codec.decode(wire);
            assertEquals(event, view.toRecord());
        }
    }

    @Test
    void byteBufferAndFfmWireBytesAreIdentical() {
        ByteBufferCodec bb = new ByteBufferCodec();
        FfmFlyweightCodec ffm = new FfmFlyweightCodec();
        for (EventRecord event : BinSerFixtures.all()) {
            assertArrayEquals(bb.encode(event), ffm.encode(event),
                    "ByteBuffer and FFM must agree on the exact same little-endian fixed layout");
        }
    }

    @Test
    void flyweightFieldAccessorsMatchWithoutMaterializing() {
        FfmFlyweightCodec codec = new FfmFlyweightCodec();
        EventRecord event = BinSerFixtures.mediumEvent();
        EventView view = codec.decode(codec.encode(event));
        assertEquals(event.id(), view.id());
        assertEquals(event.deviceId(), view.deviceId());
        assertEquals(event.value(), view.value());
        assertEquals(event.timestamp(), view.timestamp());
    }

    @Test
    void versionedOptionalFieldSlotIsAlwaysReadable() {
        // A "v1-shaped" read (ignoring optionalPresent) still gets a well-defined
        // value because the slot is always reserved in the fixed layout — this is
        // the explicit version-evolution story, not silent corruption.
        ByteBufferCodec codec = new ByteBufferCodec();
        EventRecord withOptional = BinSerFixtures.versionedOptionalField();
        byte[] wire = codec.encode(withOptional);
        EventRecord decoded = codec.decode(wire);
        assertEquals(123456, decoded.optionalField());
        assertEquals(true, decoded.optionalPresent());

        EventRecord withoutOptional = BinSerFixtures.smallCommand();
        byte[] wire2 = codec.encode(withoutOptional);
        EventRecord decoded2 = codec.decode(wire2);
        assertEquals(0, decoded2.optionalField());
        assertEquals(false, decoded2.optionalPresent());
    }
}
