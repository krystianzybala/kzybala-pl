package pl.kzybala.lab.falsesharing;

/**
 * Manual padding: 7 unused longs (56 bytes) between the two counters push
 * counterB onto the next 64-byte line on a machine with that line size —
 * a documented assumption, not a guarantee. See java.md for the risks
 * (dead-field elimination, field reordering) that {@link ContendedCounters}
 * avoids instead.
 */
@SuppressWarnings("unused")
public class PaddedCounters {
    public volatile long counterA;
    public long p1, p2, p3, p4, p5, p6, p7;
    public volatile long counterB;
}
