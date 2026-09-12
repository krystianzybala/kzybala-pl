package pl.kzybala.lab.gctail;

/** The small payload object every variant allocates (or reuses) — a value plus a small extra array. */
public final class Node {
    public long value;
    public final long[] extra;

    public Node(long value, int extraSize) {
        this.value = value;
        this.extra = new long[extraSize];
        this.extra[0] = value;
    }
}
