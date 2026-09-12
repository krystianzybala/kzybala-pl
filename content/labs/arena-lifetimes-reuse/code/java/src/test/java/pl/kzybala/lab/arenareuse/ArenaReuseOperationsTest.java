package pl.kzybala.lab.arenareuse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Correctness gate against the shared fixture
 * (../fixtures/arena-lifetimes-reuse-fixtures.json). All five lifecycle
 * variants must sum to the identical total for a given dataset —
 * allocation strategy changes B/op, allocations/op, reset/close cost
 * and contention, never the result.
 */
class ArenaReuseOperationsTest {

    @Test
    void messageBatchesAllVariantsAgree() {
        ArenaReuseFixtures.MessageSource s = ArenaReuseFixtures.generateMessages(ArenaReuseFixtures.MESSAGE_N);
        assertEquals(927524L, s.value[0]);
        assertEquals(6, s.flag[0]);

        long expected = ArenaReuseFixtures.expectedMessagesChecksum(s);
        assertEquals(374_972_485_120L, expected);

        assertEquals(expected, MessageBatchOperations.allocatePerItem(s));
        assertEquals(expected, MessageBatchOperations.batchArena(s, ArenaReuseFixtures.MESSAGE_BATCH_SIZE));
        assertEquals(expected, MessageBatchOperations.threadLocalReuse(s));

        UnboundedPool<MessageScratch> unbounded = new UnboundedPool<>(MessageScratch::new);
        assertEquals(expected, MessageBatchOperations.globalPool(s, unbounded));

        BoundedPool<MessageScratch> bounded = new BoundedPool<>(MessageBatchOperations.POOL_CAPACITY, MessageScratch::new);
        assertEquals(expected, MessageBatchOperations.boundedPool(s, bounded));
    }

    @Test
    void temporaryParseTreesAllVariantsAgree() {
        long[] value = ArenaReuseFixtures.generateTrees(ArenaReuseFixtures.DOC_COUNT, ArenaReuseFixtures.NODES_PER_DOC);
        assertEquals(419941L, value[0]);
        assertEquals(158871L, value[1]);

        long expected = ArenaReuseFixtures.expectedTreesChecksum(value);
        assertEquals(399_735_735_737L, expected);

        assertEquals(expected, ParseTreeOperations.allocatePerItem(value, ArenaReuseFixtures.NODES_PER_DOC));
        assertEquals(expected, ParseTreeOperations.batchArena(value, ArenaReuseFixtures.NODES_PER_DOC, ArenaReuseFixtures.DOCS_PER_BATCH));
        assertEquals(expected, ParseTreeOperations.threadLocalReuse(value, ArenaReuseFixtures.NODES_PER_DOC));

        UnboundedPool<long[]> unbounded = new UnboundedPool<>(() -> new long[ArenaReuseFixtures.NODES_PER_DOC]);
        assertEquals(expected, ParseTreeOperations.globalPool(value, ArenaReuseFixtures.NODES_PER_DOC, unbounded));

        BoundedPool<long[]> bounded =
            new BoundedPool<>(ParseTreeOperations.POOL_CAPACITY, () -> new long[ArenaReuseFixtures.NODES_PER_DOC]);
        assertEquals(expected, ParseTreeOperations.boundedPool(value, ArenaReuseFixtures.NODES_PER_DOC, bounded));
    }

    @Test
    void scratchBuffersAllVariantsAgree() {
        long[] word = ArenaReuseFixtures.generateBuffers(ArenaReuseFixtures.BUFFER_OP_COUNT, ArenaReuseFixtures.WORDS_PER_OP);
        assertEquals(942758L, word[0]);
        assertEquals(972169L, word[7]);

        long expected = ArenaReuseFixtures.expectedBuffersChecksum(word);
        assertEquals(799_513_392_927L, expected);

        assertEquals(expected, ScratchBufferOperations.allocatePerItem(word, ArenaReuseFixtures.WORDS_PER_OP));
        assertEquals(expected, ScratchBufferOperations.batchArena(word, ArenaReuseFixtures.WORDS_PER_OP, ArenaReuseFixtures.OPS_PER_BATCH));
        assertEquals(expected, ScratchBufferOperations.threadLocalReuse(word, ArenaReuseFixtures.WORDS_PER_OP));

        UnboundedPool<byte[]> unbounded = new UnboundedPool<>(() -> new byte[ArenaReuseFixtures.WORDS_PER_OP * 8]);
        assertEquals(expected, ScratchBufferOperations.globalPool(word, ArenaReuseFixtures.WORDS_PER_OP, unbounded));

        BoundedPool<byte[]> bounded =
            new BoundedPool<>(ScratchBufferOperations.POOL_CAPACITY, () -> new byte[ArenaReuseFixtures.WORDS_PER_OP * 8]);
        assertEquals(expected, ScratchBufferOperations.boundedPool(word, ArenaReuseFixtures.WORDS_PER_OP, bounded));
    }
}
