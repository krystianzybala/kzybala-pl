package pl.kzybala.lab.mpsc;

/** Common result shape for every fan-in variant: the consumer's full
 * received sequence (used by the correctness suite to verify per-producer
 * FIFO order and exact counts) plus contention counters. */
public final class MpscResult {
    public final long[] received;
    public final long casFailures;

    public MpscResult(long[] received, long casFailures) {
        this.received = received;
        this.casFailures = casFailures;
    }

    /** Per-producer FIFO order (the only ordering MPSC guarantees) and
     * exact total count, zero duplicates. Global cross-producer
     * interleaving is deliberately never asserted. */
    public boolean isCorrect(int producerCount, int itemsPerProducer) {
        int expectedTotal = producerCount * itemsPerProducer;
        if (received.length != expectedTotal) return false;
        int[] nextExpectedSeq = new int[producerCount];
        for (long item : received) {
            int producer = MpscFixtures.decodeProducer(item);
            int seq = MpscFixtures.decodeSeq(item);
            if (producer < 0 || producer >= producerCount) return false;
            if (seq != nextExpectedSeq[producer]) return false; // out of order or duplicate
            nextExpectedSeq[producer]++;
        }
        for (int count : nextExpectedSeq) {
            if (count != itemsPerProducer) return false;
        }
        return true;
    }
}
