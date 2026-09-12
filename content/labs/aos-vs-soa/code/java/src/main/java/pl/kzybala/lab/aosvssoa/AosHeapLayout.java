package pl.kzybala.lab.aosvssoa;

/**
 * Variant 1: AoS heap objects. Each record is its own heap object holding
 * the two hot fields directly and a reference to its own small {@code
 * long[]} for the cold payload — realistic Java heap layout: an object
 * header, two {@code long} fields, one reference field, plus a second,
 * separately-allocated array object per record. This is the layout most
 * Java code reaches for by default, and the one JOL (java.md) measures to
 * make the header/reference overhead visible rather than assumed.
 */
public final class AosHeapLayout {

    /** One record: two hot fields plus a reference to its own cold array. */
    public static final class Record {
        final long hotA;
        final long hotB;
        final long[] cold;

        Record(long hotA, long hotB, long[] cold) {
            this.hotA = hotA;
            this.hotB = hotB;
            this.cold = cold;
        }
    }

    private final Record[] records;

    private AosHeapLayout(Record[] records) {
        this.records = records;
    }

    public static AosHeapLayout of(AosVsSoaFixtures.Generated data) {
        int n = data.hotA.length;
        Record[] records = new Record[n];
        for (int i = 0; i < n; i++) {
            records[i] = new Record(data.hotA[i], data.hotB[i], data.cold[i].clone());
        }
        return new AosHeapLayout(records);
    }

    public Record[] records() {
        return records;
    }

    /** One operation = one full pass touching only the hot fields. */
    public long sumHot() {
        long sum = 0;
        for (Record r : records) sum += r.hotA + r.hotB;
        return sum;
    }

    public long coldChecksum() {
        long c = 0;
        for (Record r : records) {
            for (long v : r.cold) c = c * 31 + v;
        }
        return c;
    }
}
