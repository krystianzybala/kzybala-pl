package pl.kzybala.lab.ffmsegments;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Correctness gate against the shared fixture
 * (../fixtures/ffm-memory-segments-fixtures.json). All five storage
 * variants must sum to the identical total for a given dataset, via both
 * {@code sequentialSum} and {@code randomAccess} — storage location and
 * access path change ns/access and ns/record, never the result.
 */
class FfmMemorySegmentsOperationsTest {

    @Test
    void fixedRecordsAllVariantsAgree() {
        FfmMemorySegmentsFixtures.FixedRecordsSource s =
            FfmMemorySegmentsFixtures.generateFixedRecords(FfmMemorySegmentsFixtures.FIXED_RECORDS_N);
        assertEquals(0L, s.id[0]);
        assertEquals(580880L, s.value[0]);
        assertEquals(0, s.flag[0]);

        long expected = FfmMemorySegmentsFixtures.expectedFixedRecordsChecksum(s);
        assertEquals(375_147_506_089L, expected);

        try (RecordStorage heap = FixedRecordsStorage.heapPrimitiveArray(s)) {
            assertEquals(expected, heap.sequentialSum());
        }
        try (RecordStorage confined = FixedRecordsStorage.confinedSegment(s)) {
            assertEquals(expected, confined.sequentialSum());
        }
        try (RecordStorage shared = FixedRecordsStorage.sharedSegment(s)) {
            assertEquals(expected, shared.sequentialSum());
        }
        try (RecordStorage sliced = FixedRecordsStorage.slicedView(s)) {
            assertEquals(expected, sliced.sequentialSum());
        }
        try (RecordStorage copied = FixedRecordsStorage.copiedBoundaryCrossing(s)) {
            assertEquals(expected, copied.sequentialSum());
        }

        int[] indices = FfmMemorySegmentsFixtures.randomIndices(FfmMemorySegmentsFixtures.FIXED_RECORDS_N, 500, 900L);
        long expectedRandom;
        try (RecordStorage heap = FixedRecordsStorage.heapPrimitiveArray(s)) {
            expectedRandom = heap.randomAccess(indices);
        }
        try (RecordStorage confined = FixedRecordsStorage.confinedSegment(s)) {
            assertEquals(expectedRandom, confined.randomAccess(indices));
        }
        try (RecordStorage shared = FixedRecordsStorage.sharedSegment(s)) {
            assertEquals(expectedRandom, shared.randomAccess(indices));
        }
        try (RecordStorage sliced = FixedRecordsStorage.slicedView(s)) {
            assertEquals(expectedRandom, sliced.randomAccess(indices));
        }
        try (RecordStorage copied = FixedRecordsStorage.copiedBoundaryCrossing(s)) {
            assertEquals(expectedRandom, copied.randomAccess(indices));
        }
    }

    @Test
    void largeNumericBuffersAllVariantsAgree() {
        long[] value = FfmMemorySegmentsFixtures.generateLargeBuffer(FfmMemorySegmentsFixtures.LARGE_BUFFER_N);
        assertEquals(646811153L, value[0]);

        long expected = FfmMemorySegmentsFixtures.expectedLargeBufferChecksum(value);
        assertEquals(2_499_690_517_128_328L, expected);

        try (RecordStorage heap = LargeNumericBufferStorage.heapPrimitiveArray(value)) {
            assertEquals(expected, heap.sequentialSum());
        }
        try (RecordStorage confined = LargeNumericBufferStorage.confinedSegment(value)) {
            assertEquals(expected, confined.sequentialSum());
        }
        try (RecordStorage shared = LargeNumericBufferStorage.sharedSegment(value)) {
            assertEquals(expected, shared.sequentialSum());
        }
        try (RecordStorage sliced = LargeNumericBufferStorage.slicedView(value)) {
            assertEquals(expected, sliced.sequentialSum());
        }
        try (RecordStorage copied = LargeNumericBufferStorage.copiedBoundaryCrossing(value)) {
            assertEquals(expected, copied.sequentialSum());
        }

        int[] indices = FfmMemorySegmentsFixtures.randomIndices(FfmMemorySegmentsFixtures.LARGE_BUFFER_N, 500, 901L);
        long expectedRandom;
        try (RecordStorage heap = LargeNumericBufferStorage.heapPrimitiveArray(value)) {
            expectedRandom = heap.randomAccess(indices);
        }
        try (RecordStorage confined = LargeNumericBufferStorage.confinedSegment(value)) {
            assertEquals(expectedRandom, confined.randomAccess(indices));
        }
        try (RecordStorage sliced = LargeNumericBufferStorage.slicedView(value)) {
            assertEquals(expectedRandom, sliced.randomAccess(indices));
        }
    }

    @Test
    void binaryFramesAllVariantsAgree() {
        FfmMemorySegmentsFixtures.BinaryFramesSource s =
            FfmMemorySegmentsFixtures.generateBinaryFrames(FfmMemorySegmentsFixtures.BINARY_FRAMES_N);
        assertEquals(2, s.type[0]);
        assertEquals(724347L, s.payload[0][0]);

        long expected = FfmMemorySegmentsFixtures.expectedBinaryFramesChecksum(s);
        assertEquals(300_295_670_129L, expected);

        try (RecordStorage heap = BinaryFramesStorage.heapPrimitiveArray(s)) {
            assertEquals(expected, heap.sequentialSum());
        }
        try (RecordStorage confined = BinaryFramesStorage.confinedSegment(s)) {
            assertEquals(expected, confined.sequentialSum());
        }
        try (RecordStorage shared = BinaryFramesStorage.sharedSegment(s)) {
            assertEquals(expected, shared.sequentialSum());
        }
        try (RecordStorage sliced = BinaryFramesStorage.slicedView(s)) {
            assertEquals(expected, sliced.sequentialSum());
        }
        try (RecordStorage copied = BinaryFramesStorage.copiedBoundaryCrossing(s)) {
            assertEquals(expected, copied.sequentialSum());
        }

        int[] indices = FfmMemorySegmentsFixtures.randomIndices(FfmMemorySegmentsFixtures.BINARY_FRAMES_N, 500, 902L);
        long expectedRandom;
        try (RecordStorage heap = BinaryFramesStorage.heapPrimitiveArray(s)) {
            expectedRandom = heap.randomAccess(indices);
        }
        try (RecordStorage confined = BinaryFramesStorage.confinedSegment(s)) {
            assertEquals(expectedRandom, confined.randomAccess(indices));
        }
        try (RecordStorage shared = BinaryFramesStorage.sharedSegment(s)) {
            assertEquals(expectedRandom, shared.randomAccess(indices));
        }
        try (RecordStorage sliced = BinaryFramesStorage.slicedView(s)) {
            assertEquals(expectedRandom, sliced.randomAccess(indices));
        }
        try (RecordStorage copied = BinaryFramesStorage.copiedBoundaryCrossing(s)) {
            assertEquals(expectedRandom, copied.randomAccess(indices));
        }
    }
}
