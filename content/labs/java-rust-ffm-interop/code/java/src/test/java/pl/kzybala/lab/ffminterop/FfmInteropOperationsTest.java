package pl.kzybala.lab.ffminterop;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class FfmInteropOperationsTest {

    private static double[] sampleInput(int n) {
        double[] input = new double[n];
        for (int i = 0; i < n; i++) {
            input[i] = i * 0.5 - 3.0;
        }
        return input;
    }

    private static double[] expected(double[] input) {
        double[] out = new double[input.length];
        for (int i = 0; i < input.length; i++) {
            out[i] = input[i] * 2.0 + 1.0;
        }
        return out;
    }

    @Test
    void pureJavaBaselineMatchesFormula() {
        double[] input = sampleInput(200);
        assertArrayEquals(expected(input), FfmInteropOps.pureJavaBaseline(input));
    }

    @Test
    void scalarDowncallMatchesPureJava() {
        double[] input = sampleInput(200);
        assertArrayEquals(expected(input), FfmInteropOps.scalarDowncall(input));
    }

    @Test
    void batchedDowncallMatchesPureJava() {
        double[] input = sampleInput(500);
        assertArrayEquals(expected(input), FfmInteropOps.batchedDowncall(input));
    }

    @Test
    void zeroCopyDowncallMatchesPureJava() {
        double[] input = sampleInput(500);
        double[] expected = expected(input);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate((long) input.length * Double.BYTES);
            MemorySegment.copy(input, 0, segment, ValueLayout.JAVA_DOUBLE, 0, input.length);
            FfmInteropOps.zeroCopyDowncall(segment, input.length);
            double[] actual = new double[input.length];
            MemorySegment.copy(segment, ValueLayout.JAVA_DOUBLE, 0, actual, 0, input.length);
            assertArrayEquals(expected, actual);
        }
    }

    @Test
    void allFourTransformVariantsAgreeWithEachOther() {
        double[] input = sampleInput(300);
        double[] pureJava = FfmInteropOps.pureJavaBaseline(input);
        double[] scalar = FfmInteropOps.scalarDowncall(input);
        double[] batched = FfmInteropOps.batchedDowncall(input);
        assertArrayEquals(pureJava, scalar);
        assertArrayEquals(pureJava, batched);
    }

    @Test
    void validateRecordsReturnsZeroForAllValidRecords() {
        byte[] records = FixedRecordFormat.buildRecords(50, FixedRecordFormat::expectedValue);
        assertEquals(0, FfmInteropOps.validateRecords(records));
    }

    @Test
    void validateRecordsCountsCorruptedRecords() {
        byte[] records = FixedRecordFormat.buildRecords(50, id -> id == 7 || id == 20 ? 999L : FixedRecordFormat.expectedValue(id));
        assertEquals(2, FfmInteropOps.validateRecords(records));
    }

    @Test
    void validateRecordsRejectsMisalignedLength() {
        byte[] misaligned = new byte[FixedRecordFormat.RECORD_SIZE + 1];
        assertEquals(-1, FfmInteropOps.validateRecords(misaligned));
    }

    @Test
    void upcallTransformDeliversEveryElementInOrder() {
        double[] input = sampleInput(64);
        List<Double> received = FfmInteropOps.upcallTransform(input);
        assertEquals(input.length, received.size());
        double[] expected = expected(input);
        for (int i = 0; i < input.length; i++) {
            assertEquals(expected[i], received.get(i), 1e-9);
        }
    }

    @Test
    void upcallTransformConsumerVariantDeliversEveryElement() {
        double[] input = sampleInput(64);
        double[] expected = expected(input);
        List<Double> received = new ArrayList<>();
        FfmInteropOps.upcallTransform(input, received::add);
        assertEquals(input.length, received.size());
        for (int i = 0; i < input.length; i++) {
            assertEquals(expected[i], received.get(i), 1e-9);
        }
    }
}
