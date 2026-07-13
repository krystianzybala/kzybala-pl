package pl.kzybala.lab.falsesharing;

/**
 * The bug: two independent counters, adjacent fields of the same object.
 * Nothing here is a data race — each thread only ever writes its own field —
 * but on a machine where both fields land on one cache line, every write
 * invalidates the other thread's cached copy of the line. See java.md.
 */
public class SharedCounters {
    public volatile long counterA;
    public volatile long counterB;
}
