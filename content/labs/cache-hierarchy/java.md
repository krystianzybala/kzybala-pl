# Cache hierarchy — Java

The benchmark below uses **pointer chasing**: each memory access reads a
value that determines the *next* address to read, creating a dependent
chain the CPU cannot parallelize, reorder around, or predict without
actually resolving each step. This is the standard technique for isolating
memory latency — a plain loop over `array[i]` can be partly hidden by
out-of-order execution or auto-vectorized in ways that no longer reflect a
single access's true cost.

## Building the chase tables

```java
package pl.kzybala.lab.cachehierarchy;

import java.util.Random;

/**
 * Builds pointer-chase tables for the cache-hierarchy benchmark. Each
 * table is a single N-element cycle: starting at index 0 and repeatedly
 * following {@code next[idx]} visits every element exactly once before
 * returning to 0. See CacheHierarchyBenchmark for how these are used.
 */
public final class ChaseTables {
    private ChaseTables() {}

    /** A sequential cycle: 0 -&gt; 1 -&gt; 2 -&gt; ... -&gt; size-1 -&gt; 0. Maximal spatial locality. */
    public static long[] sequentialCycle(int size) {
        long[] next = new long[size];
        for (int i = 0; i < size; i++) next[i] = (i + 1) % size;
        return next;
    }

    /**
     * A random single-cycle permutation built with Sattolo's algorithm.
     * Plain Fisher-Yates would risk producing several short sub-cycles,
     * which would let a pointer chase loop through only a small hot
     * subset of the array — Sattolo's algorithm guarantees exactly one
     * cycle covering all {@code size} elements.
     */
    public static long[] randomCycle(int size, long seed) {
        int[] perm = new int[size];
        for (int i = 0; i < size; i++) perm[i] = i;
        Random rnd = new Random(seed);
        for (int i = size - 1; i > 0; i--) {
            // nextInt(i), not nextInt(i + 1) as in Fisher-Yates — this
            // exclusion of self-swaps is what makes Sattolo's algorithm
            // produce a single cycle instead of a random permutation
            // (which is usually several disjoint cycles).
            int j = rnd.nextInt(i);
            int tmp = perm[i]; perm[i] = perm[j]; perm[j] = tmp;
        }
        long[] next = new long[size];
        for (int i = 0; i < size; i++) next[perm[i]] = perm[(i + 1) % size];
        return next;
    }
}
```

## The benchmark

```java
package pl.kzybala.lab.cachehierarchy;

import org.openjdk.jmh.annotations.*;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class CacheHierarchyBenchmark {

    // 16 KB of longs — comfortably fits inside L1D on any current desktop/laptop core.
    private static final int SMALL_SIZE = 2_048;
    // 128 MB of longs — exceeds any consumer last-level cache.
    private static final int LARGE_SIZE = 16_777_216;
    private static final int CHASES = 1_000_000;

    private long[] sequentialSmallTable;
    private long[] randomSmallTable;
    private long[] sequentialLargeTable;
    private long[] randomLargeTable;

    @Setup(Level.Trial)
    public void setup() {
        sequentialSmallTable = ChaseTables.sequentialCycle(SMALL_SIZE);
        randomSmallTable = ChaseTables.randomCycle(SMALL_SIZE, 1);
        sequentialLargeTable = ChaseTables.sequentialCycle(LARGE_SIZE);
        randomLargeTable = ChaseTables.randomCycle(LARGE_SIZE, 2);
    }

    private static long chase(long[] next, int steps) {
        long idx = 0;
        for (int i = 0; i < steps; i++) idx = next[(int) idx];
        return idx; // returned (not discarded) so the JIT cannot eliminate the loop as dead code.
    }

    @Benchmark public long sequentialSmall() { return chase(sequentialSmallTable, CHASES); }
    @Benchmark public long randomSmall() { return chase(randomSmallTable, CHASES); }
    @Benchmark public long sequentialLarge() { return chase(sequentialLargeTable, CHASES); }
    @Benchmark public long randomLarge() { return chase(randomLargeTable, CHASES); }
}
```

Each benchmark method reports the average wall-clock time for one batch of
`CHASES` (1,000,000) dependent chases. Dividing that by `CHASES` gives an
approximate per-access latency — see `benchmark.md` for the measured
numbers and that division worked out.

**Why `next[(int) idx]` and not a generic collection?** A raw `long[]`
avoids the extra pointer indirection and boxing overhead that something
like `ArrayList<Long>` would add on top of the memory-hierarchy effect
being measured — the goal is to isolate hierarchy latency, not allocator or
boxing overhead.

**Why AverageTime, not Throughput?** Throughput mode (as used in the False
sharing lab) suits *independent*, parallelizable operations, where "how
many complete per second" is the interesting number. This benchmark's
operations are a single dependent chain — there's exactly one chase in
flight at a time — so average wall-clock time per batch is the more direct
measurement.

**What isn't eliminated, and what isn't controlled.** Two things worth
being explicit about, since they affect how far these numbers generalize:

- `next[(int) idx]` still performs a normal Java array bounds check on
  every access. HotSpot's C2 compiler can only remove a bounds check when
  it can statically prove the index stays in range (its range-check
  elimination pass targets loop-counter-derived indices); `idx` here comes
  from the array's own contents, not the loop counter, so it is not
  eligible and the check is not expected to be removed. This cost is
  small, branch-predicts well, and — importantly — applies equally to
  every benchmark in this file, so it does not bias the sequential/random
  comparison.
- Neither this benchmark nor its Rust counterpart pins the process to a
  specific CPU core. Apple silicon's performance (P) and efficiency (E)
  cores have different cache sizes and clock speeds, and macOS's own
  thread-affinity API is documented as an advisory hint rather than a hard
  pin on Apple silicon — unlike Linux, there is no `taskset`-equivalent
  guarantee available from user space. A run that gets scheduled onto (or
  migrated to) an E-core, or that shares a P-core with other load on the
  machine, will show more variance than one that doesn't — see
  `benchmark.md` for a concrete example of this observed during this
  lab's own benchmark reruns.

The runnable Maven/JMH project is at <a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/cache-hierarchy/code/java" rel="noopener"><code>content/labs/cache-hierarchy/code/java/</code></a> in this site's repository.
