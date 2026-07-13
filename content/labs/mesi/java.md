# Cache coherence and MESI — Java

Two small, deliberately non-benchmarked examples. Neither one measures
throughput — they demonstrate the two software-level shapes that produce
very different coherence traffic: a field written by multiple threads on
different cores, and a field only ever touched by one thread.

## Shared-writer example

```java
public class SharedWriterExample {
    // Two threads both write this counter, from different cores — the JVM
    // guarantees atomicity and visibility, but says nothing about, and does
    // not let you observe, which coherence protocol state the line is in.
    private final AtomicLong counter = new AtomicLong();

    public void incrementFrom(int iterations) {
        for (int i = 0; i < iterations; i++) counter.incrementAndGet();
    }

    public long value() { return counter.get(); }
}
```

Two threads calling `incrementFrom` concurrently on the same instance
produce exactly the coherence traffic the interactive model calls
"competing writers": every increment from one thread invalidates the
other's cached copy of the line. `AtomicLong` guarantees the arithmetic is
correct regardless — this example demonstrates the coherence *cost* of
shared mutable state, not a correctness bug.

## Single-writer ownership example

```java
public class SingleOwnerExample {
    // No synchronization needed: exactly one thread ever reads or writes
    // this field for the object's whole lifetime. In coherence terms, that
    // thread's core can hold this line Exclusive/Modified indefinitely and
    // never has to respond to another core's invalidation.
    private long total;

    public void addFrom(int iterations) {
        for (int i = 0; i < iterations; i++) total += i;
    }

    public long total() { return total; }
}
```

Not synchronized, and correct anyway — because only one thread ever touches
`total`. This is the software-level analogue of the model's
Exclusive/Modified single-owner path: no other core contends for the line,
so there is no invalidation traffic to pay for. Contrast with
`SharedWriterExample`, where two threads writing the same field is what
produces cross-core invalidation.

**These examples do not, and cannot, directly control which MESI state a
line is in.** The JVM and JIT give no API for observing or forcing
coherence states — `perf c2c` (see "Diagnostic methodology" in `theory.md`)
is how you observe the resulting traffic on Linux, not the source code.

The runnable project is in `code/java/` alongside this file — see
`README.md` there for build and run instructions.
