package pl.kzybala.lab.ffmsegments;

/** Common shape every storage variant implements — one operation per method (benchmark.md's contract). */
public interface RecordStorage extends AutoCloseable {

    /** One full sequential pass over every record, summing its numeric fields. */
    long sequentialSum();

    /** Sum the numeric fields of exactly {@code indices.length} records, in the given (pseudo-random) order. */
    long randomAccess(int[] indices);

    @Override
    void close();
}
