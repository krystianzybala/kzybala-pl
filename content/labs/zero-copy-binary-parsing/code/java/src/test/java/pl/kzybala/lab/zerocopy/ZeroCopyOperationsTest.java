package pl.kzybala.lab.zerocopy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static pl.kzybala.lab.zerocopy.ZeroCopyFixtures.WireBuffer;

/**
 * Correctness gate against the shared fixture
 * (../fixtures/zero-copy-binary-parsing-fixtures.json). All five decoder
 * variants must sum to the identical total for a given dataset —
 * decoding strategy changes ns/message, B/message, bytes copied and
 * validation cost, never the result.
 */
class ZeroCopyOperationsTest {

    @Test
    void fixedHeaderPlusVariablePayloadAllVariantsAgree() {
        WireBuffer wb = ZeroCopyFixtures.encodeFixedHeader(ZeroCopyFixtures.FIXED_HEADER_N);
        long expected = ZeroCopyFixtures.expectedFixedHeaderChecksum(wb, ZeroCopyFixtures.FIXED_HEADER_N);
        assertEquals(1_519_448_080_523L, expected);

        assertEquals(expected, FixedHeaderDecoders.copyingDecoder(wb, ZeroCopyFixtures.FIXED_HEADER_N));
        assertEquals(expected, FixedHeaderDecoders.objectBuildingDecoder(wb, ZeroCopyFixtures.FIXED_HEADER_N));
        assertEquals(expected, FixedHeaderDecoders.validatedZeroCopyView(wb, ZeroCopyFixtures.FIXED_HEADER_N));
        assertEquals(expected, FixedHeaderDecoders.lazyFieldDecode(wb, ZeroCopyFixtures.FIXED_HEADER_N));
        assertEquals(expected, FixedHeaderDecoders.mutableInPlaceUpdate(wb, ZeroCopyFixtures.FIXED_HEADER_N));
    }

    @Test
    void nestedRepeatedFieldsAllVariantsAgree() {
        WireBuffer wb = ZeroCopyFixtures.encodeNested(ZeroCopyFixtures.NESTED_N);
        long expected = ZeroCopyFixtures.expectedNestedChecksum(wb, ZeroCopyFixtures.NESTED_N);
        assertEquals(3_861_687_000_575L, expected);

        assertEquals(expected, NestedRepeatedDecoders.copyingDecoder(wb, ZeroCopyFixtures.NESTED_N));
        assertEquals(expected, NestedRepeatedDecoders.objectBuildingDecoder(wb, ZeroCopyFixtures.NESTED_N));
        assertEquals(expected, NestedRepeatedDecoders.validatedZeroCopyView(wb, ZeroCopyFixtures.NESTED_N));
        assertEquals(expected, NestedRepeatedDecoders.lazyFieldDecode(wb, ZeroCopyFixtures.NESTED_N));
        assertEquals(expected, NestedRepeatedDecoders.mutableInPlaceUpdate(wb, ZeroCopyFixtures.NESTED_N));
    }

    @Test
    void utf8FieldAllVariantsAgree() {
        WireBuffer wb = ZeroCopyFixtures.encodeUtf8(ZeroCopyFixtures.UTF8_N);
        long expected = ZeroCopyFixtures.expectedUtf8Checksum(wb, ZeroCopyFixtures.UTF8_N);
        assertEquals(11_605_683_083L, expected);

        assertEquals(expected, Utf8FieldDecoders.copyingDecoder(wb, ZeroCopyFixtures.UTF8_N));
        assertEquals(expected, Utf8FieldDecoders.objectBuildingDecoder(wb, ZeroCopyFixtures.UTF8_N));
        assertEquals(expected, Utf8FieldDecoders.validatedZeroCopyView(wb, ZeroCopyFixtures.UTF8_N));
        assertEquals(expected, Utf8FieldDecoders.lazyFieldDecode(wb, ZeroCopyFixtures.UTF8_N));
        assertEquals(expected, Utf8FieldDecoders.mutableInPlaceUpdate(wb, ZeroCopyFixtures.UTF8_N));
    }

    @Test
    void utf8ValidationRejectsInvalidBytes() {
        java.lang.foreign.MemorySegment segment =
            java.lang.foreign.MemorySegment.ofArray(new byte[] {(byte) 0xC0, (byte) 0xC0, 0x41, 0x42});
        assertTrue(!ZeroCopyFixtures.isValidUtf8(segment, 0, 2)); // 0xC0 0xC0 is an invalid/overlong UTF-8 lead sequence
        assertTrue(ZeroCopyFixtures.isValidUtf8(segment, 2, 2)); // "AB" is valid
    }
}
