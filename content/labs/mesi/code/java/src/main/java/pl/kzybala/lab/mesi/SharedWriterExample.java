package pl.kzybala.lab.mesi;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Companion code for the "Cache coherence and MESI" Performance Lab
 * (kzybala.pl/lab/mesi/). Two threads writing this counter from different
 * cores reproduce the "competing writers" coherence traffic the lab's
 * interactive model shows: every increment from one core invalidates the
 * other's cached copy of the line. See java.md for the full explanation.
 */
public class SharedWriterExample {

    private final AtomicLong counter = new AtomicLong();

    public void incrementFrom(int iterations) {
        for (int i = 0; i < iterations; i++) counter.incrementAndGet();
    }

    public long value() {
        return counter.get();
    }
}
