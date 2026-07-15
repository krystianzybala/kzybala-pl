# False sharing — Java

Four variants of the same benchmark: counters incremented in a tight loop,
each by its own dedicated thread. Only the memory layout and ownership
structure change — visibility guarantees stay constant across variants.

## Shared counters (the bug)

```java
public class SharedCounters {
    // Adjacent fields of the same object are very likely to land on the
    // same 64-byte cache line — the JVM makes no promise either way, but in
    // practice contiguous long fields with no padding between them do.
    public volatile long counterA;
    public volatile long counterB;
}
```

Thread 1 spins on `counterA++`, thread 2 spins on `counterB++`. Neither
thread ever touches the other's field — there is no data race, no lock, no
shared invariant. But every write to `counterA` invalidates core 2's cached
copy of the line holding `counterB`, and every write to `counterB`
invalidates core 1's copy right back. Throughput drops relative to running
either loop alone, even though the two threads are logically independent.

## Manual padding (the fix)

```java
public class PaddedCounters {
    public volatile long counterA;
    // 7 longs = 56 bytes of padding. Combined with the 8-byte counterA,
    // that pushes counterB onto the next 64-byte line on a machine with
    // that line size — this is a documented assumption, not a guarantee.
    public long p1, p2, p3, p4, p5, p6, p7;
    public volatile long counterB;
}
```

The padding fields are never read after construction — only present to force
a layout gap. Two risks to call out explicitly:

1. **The JIT can eliminate unread fields it proves are dead**, which would
   silently remove your padding and reintroduce false sharing. In practice
   HotSpot does not currently do this for instance fields with this shape,
   but the language spec does not forbid it. Prefer `@Contended` (below) where
   available precisely because it does not rely on this being true forever.
2. **Field reordering.** The JVM is free to reorder fields within an object
   for alignment; padding by declaration order is a *practical*, not
   *guaranteed*, technique. `@Contended` sidesteps this too.

## `@Contended` (the supported fix)

```java
import jdk.internal.vm.annotation.Contended;

public class ContendedCounters {
    @Contended
    public volatile long counterA;
    @Contended
    public volatile long counterB;
}
```

**Caveat:** `@Contended` lives in `jdk.internal.vm.annotation`, an internal
package. Application code needs `--add-exports
java.base/jdk.internal.vm.annotation=ALL-UNNAMED` (or the module-specific
equivalent) on the JVM command line to use it outside the JDK itself, and
Oracle/OpenJDK gives no compatibility promise across major versions for
internal packages. `@Contended` also pads to a JVM-internal contended-group
size (`-XX:ContendedPaddingWidth`, default 128 bytes — two typical cache
lines — to also guard against adjacent-line prefetch, see "Prefetch" in the
trade-offs section), not the value you write in source. Use it when you
control the deployment JVM flags; use manual padding when you need it to
compile and run unmodified across arbitrary JVMs.

## Per-thread shards + reduction (removing the problem instead of padding it)

```java
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public final class ShardedCounters {
    static final int STRIDE = 8; // 8 longs = 64 bytes per shard (documented assumption)
    private static final VarHandle SHARDS =
        MethodHandles.arrayElementVarHandle(long[].class);

    private final long[] shards;

    public ShardedCounters(int shardCount) {
        // +2: leading/trailing pad regions so shard 0 / shard N-1 don't
        // share a line with the array header or a neighboring allocation.
        this.shards = new long[(shardCount + 2) * STRIDE];
    }

    /** Owner-only: each shard has exactly one writer thread. */
    public void add(int shard, long delta) {
        int i = (shard + 1) * STRIDE;
        long current = (long) SHARDS.get(shards, i);      // plain read: we are the only writer
        SHARDS.setRelease(shards, i, current + delta);    // release write: publishes to reducers
    }

    /** Exact only after the owner threads have been joined. */
    public long total() { /* acquire-read every shard and sum — see code/java */ }
}
```

Padding treats the symptom — the counters still logically belong to "one
object two threads write." Sharding removes the disease: each thread writes
only memory it exclusively owns, so no cache line is ever written from two
cores and there is nothing left to pad against. The price is a reduction
step on read and `shardCount × 64` bytes of memory.

This variant also demonstrates **VarHandles** as the modern replacement for
both `volatile` field access and `Unsafe`-based tricks: because each shard
has exactly one writer, the owner needs no atomic read-modify-write at all —
a plain read plus a `setRelease` write is sufficient, and the reducer pairs
it with `getAcquire`. That is a *weaker, cheaper* contract than a `volatile`
increment, and it is only correct because the single-writer invariant holds.
The correctness test (`CounterCorrectnessTest`) asserts exactly that
invariant's observable result against the shared fixture.

## JMH benchmark

```java
import org.openjdk.jmh.annotations.*;
import java.util.concurrent.TimeUnit;

@State(Scope.Group)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = {"-XX:-RestrictContended"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class FalseSharingBenchmark {

    private final SharedCounters shared = new SharedCounters();
    private final PaddedCounters padded = new PaddedCounters();

    @Benchmark
    @Group("shared")
    public void writeA_shared() { shared.counterA++; }

    @Benchmark
    @Group("shared")
    public void writeB_shared() { shared.counterB++; }

    @Benchmark
    @Group("padded")
    public void writeA_padded() { padded.counterA++; }

    @Benchmark
    @Group("padded")
    public void writeB_padded() { padded.counterB++; }

    // ... "contended" and "sharded" groups follow the same two-writer
    // pattern — see code/java/ for the complete runnable class.
}
```

`@Group` with `Scope.Group` is what makes JMH run `writeA_*` and `writeB_*`
concurrently on separate threads within the same group, rather than each in
isolation — without it the benchmark would never reproduce the cross-core
invalidation traffic being measured. `-XX:-RestrictContended` is required to
let `@Contended` take effect outside `java.base` on some JDK versions; check
your target JDK's default.

**Thread assignment:** JMH pins one thread per method in a `@Group` by
default (`threads` defaults to the number of `@Benchmark` methods in the
group, one each); pass an explicit `@Threads` and a `ThreadParams`-aware
setup only if you need more producers per role. The runnable project is in
`code/java/` alongside this file — see `README.md` there for build and run
instructions.

## Publication-evidence benchmark (native Linux)

`FalseSharingLinuxEvidenceBenchmark` is a separate, parameterized class the
native-Linux evidence runner drives with `-p layout=shared` or `-p
layout=padded`, one variant per JVM invocation, exactly two group worker
threads, and a trial teardown that fails the run if either counter did not
advance. Its layout claims are **verified, not assumed**: `CounterLayoutTest`
reads the real field offsets with JOL and fails if `SharedCounters` stops
being adjacent, or `PaddedCounters`/`@Contended` counters fall closer than
64 bytes under the documented flags. See `docs/linux-evidence-runner.md`
for the full collection workflow (`perf stat`, `perf c2c`, provenance and
review policy).
