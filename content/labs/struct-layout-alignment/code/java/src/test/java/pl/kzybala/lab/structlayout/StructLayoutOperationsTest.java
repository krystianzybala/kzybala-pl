package pl.kzybala.lab.structlayout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Correctness gate against the shared fixture
 * (../fixtures/struct-layout-alignment-fixtures.json). All five layout
 * variants must sum to the identical total for a given dataset (or, for
 * {@code producerConsumerCounters}, reproduce the identical final
 * counts) — layout changes bytes/record, ns/access and coherence
 * traffic, never the result.
 */
class StructLayoutOperationsTest {

    @Test
    void mixedPrimitiveRecordAllVariantsAgree() {
        StructLayoutFixtures.MixedSource s = StructLayoutFixtures.generateMixed(StructLayoutFixtures.MIXED_N);
        assertEquals(0, s.active[0]);
        assertEquals(0, s.timestamp[0]);
        assertEquals(7, s.category[0]);
        assertEquals(17098, s.quantity[0]);
        assertEquals(759, s.flags[0]);
        assertEquals(884393L, s.amountTicks[0]);

        long expected = StructLayoutFixtures.expectedMixedChecksum(s);
        assertEquals(2_648_193_599_320L, expected);

        try (MixedRecordLayout l = MixedRecordLayout.natural(s)) {
            assertEquals(expected, l.sum());
            assertEquals(32, l.strideBytes());
        }
        try (MixedRecordLayout l = MixedRecordLayout.poorFieldOrder(s)) {
            assertEquals(expected, l.sum());
            assertEquals(40, l.strideBytes());
        }
        try (MixedRecordLayout l = MixedRecordLayout.optimizedFieldOrder(s)) {
            assertEquals(expected, l.sum());
            assertEquals(24, l.strideBytes());
        }
        try (MixedRecordLayout l = MixedRecordLayout.cacheLineAligned(s)) {
            assertEquals(expected, l.sum());
            assertEquals(64, l.strideBytes());
        }
        try (MixedRecordLayout l = MixedRecordLayout.packedUnaligned(s)) {
            assertEquals(expected, l.sum());
            assertEquals(24, l.strideBytes());
        }
    }

    @Test
    void headerPlusPayloadAllVariantsAgree() {
        StructLayoutFixtures.HeaderSource s = StructLayoutFixtures.generateHeader(StructLayoutFixtures.HEADER_N);
        assertEquals(3, s.msgType[0]);
        assertEquals(182, s.msgFlags[0]);
        assertEquals(552163, s.sequence[0]);
        assertEquals(736058L, s.payload[0][0]);

        long expected = StructLayoutFixtures.expectedHeaderChecksum(s);
        assertEquals(899_586_356_734L, expected);

        try (HeaderPayloadLayout l = HeaderPayloadLayout.natural(s)) {
            assertEquals(expected, l.sum());
            assertEquals(72, l.strideBytes());
        }
        try (HeaderPayloadLayout l = HeaderPayloadLayout.poorFieldOrder(s)) {
            assertEquals(expected, l.sum());
            assertEquals(80, l.strideBytes());
        }
        try (HeaderPayloadLayout l = HeaderPayloadLayout.optimizedFieldOrder(s)) {
            assertEquals(expected, l.sum());
            assertEquals(72, l.strideBytes());
        }
        try (HeaderPayloadLayout l = HeaderPayloadLayout.cacheLineAligned(s)) {
            assertEquals(expected, l.sum());
            assertEquals(128, l.strideBytes());
        }
        try (HeaderPayloadLayout l = HeaderPayloadLayout.packedUnaligned(s)) {
            assertEquals(expected, l.sum());
            assertEquals(70, l.strideBytes());
        }
    }

    private static final long INCREMENTS = 100_000;

    private static void runJoined(Runnable a, Runnable b) throws InterruptedException {
        Thread t1 = new Thread(a);
        Thread t2 = new Thread(b);
        t1.start();
        t2.start();
        t1.join();
        t2.join();
    }

    @Test
    void producerConsumerCountersAllVariantsCountExactly() throws InterruptedException {
        ProducerConsumerCounters.Natural natural = new ProducerConsumerCounters.Natural();
        runJoined(
            () -> { for (long i = 0; i < INCREMENTS; i++) natural.producerCount++; },
            () -> { for (long i = 0; i < INCREMENTS; i++) natural.consumerCount++; });
        assertEquals(INCREMENTS, natural.producerCount);
        assertEquals(INCREMENTS, natural.consumerCount);

        ProducerConsumerCounters.PoorFieldOrder poor = new ProducerConsumerCounters.PoorFieldOrder();
        runJoined(
            () -> { for (long i = 0; i < INCREMENTS; i++) poor.producerCount++; },
            () -> { for (long i = 0; i < INCREMENTS; i++) poor.consumerCount++; });
        assertEquals(INCREMENTS, poor.producerCount);
        assertEquals(INCREMENTS, poor.consumerCount);

        ProducerConsumerCounters.OptimizedFieldOrder optimized = new ProducerConsumerCounters.OptimizedFieldOrder();
        runJoined(
            () -> { for (long i = 0; i < INCREMENTS; i++) optimized.producerCount++; },
            () -> { for (long i = 0; i < INCREMENTS; i++) optimized.consumerCount++; });
        assertEquals(INCREMENTS, optimized.producerCount);
        assertEquals(INCREMENTS, optimized.consumerCount);

        ProducerConsumerCounters.CacheLineAligned aligned = new ProducerConsumerCounters.CacheLineAligned();
        runJoined(
            () -> { for (long i = 0; i < INCREMENTS; i++) aligned.producerCount++; },
            () -> { for (long i = 0; i < INCREMENTS; i++) aligned.consumerCount++; });
        assertEquals(INCREMENTS, aligned.producerCount);
        assertEquals(INCREMENTS, aligned.consumerCount);

        ProducerConsumerCounters.PackedUnaligned packed = new ProducerConsumerCounters.PackedUnaligned();
        runJoined(
            () -> { for (long i = 0; i < INCREMENTS; i++) packed.producerCount++; },
            () -> { for (long i = 0; i < INCREMENTS; i++) packed.consumerCount++; });
        assertEquals(INCREMENTS, packed.producerCount);
        assertEquals(INCREMENTS, packed.consumerCount);
    }
}
