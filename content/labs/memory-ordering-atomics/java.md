# Memory Ordering: VarHandles and Rust Atomics — Java

## Shared fixtures

```java
public final class MemOrdFixtures {
    public static final int MAILBOX_PAYLOAD_A = 123_456_789;
    public static final int MAILBOX_PAYLOAD_B = 987_654_321;
    public static final int MAILBOX_TRIALS = 2_000;

    public static final int SEQFLAG_UPDATE_COUNT = 2_000;
    public static final int SEQFLAG_TRIALS = 50;
    public static int seqflagPayload(int seq) { return seq * 7 + 3; }

    public static final int COUNTER_THREAD_COUNT = 4;
    public static final int COUNTER_INCREMENTS_PER_THREAD = 50_000;
    public static final long COUNTER_EXPECTED_TOTAL =
            (long) COUNTER_THREAD_COUNT * COUNTER_INCREMENTS_PER_THREAD;

    public static final long MAX_SPIN_ITERATIONS = 200_000_000L;
}
```

These constants are identical, byte-for-byte, to
`code/fixtures/memory-ordering-atomics-fixtures.json` and to the Rust
crate's own `fixtures` module (rust.md) — the shared correctness fixture
both languages assert against.

## Single-slot mailbox

```java
public final class MailboxKernel {
    static final class Slot { int a; int b; int ready; }

    private static final VarHandle READY; // findVarHandle(Slot.class, "ready", int.class)

    public static Outcome acquireReleasePublication() throws InterruptedException {
        Slot s = new Slot();
        Thread writer = new Thread(() -> {
            s.a = MemOrdFixtures.MAILBOX_PAYLOAD_A;
            s.b = MemOrdFixtures.MAILBOX_PAYLOAD_B;
            READY.setRelease(s, 1);
        });
        Thread reader = new Thread(() -> {
            boolean got = spinUntil(() -> (int) READY.getAcquire(s) != 0);
            /* ... check payload once got ... */
        });
        // start both, join both, return the reader's outcome
    }
}
```

`plainBrokenPublication()` is identical except the flag is a bare `s.ready
= 1` / `s.ready != 0` — a plain field, no `VarHandle` involved at all.
`volatileSeqCstPublication()` swaps in `setVolatile`/`getVolatile`.
`casLoop()` uses `READY.compareAndSet` twice — once for the writer's
0→1 publish, once for the reader's 1→2 claim, making the hand-off
exactly-once rather than a one-way flag. All four "correct" variants pass
`MemOrdFixtures.MAILBOX_TRIALS` trials with zero forbidden reads and zero
timeouts; the plain variant is run as a demonstration only (see
exercises.md and the correctness test below).

The full source, including every variant, is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/memory-ordering-atomics/code/java/src/main/java/pl/kzybala/lab/memord/MailboxKernel.java" rel="noopener"><code>code/java/.../MailboxKernel.java</code></a>
in this site's repository.

## Sequence flag plus payload (a seqlock) — and two real bugs found building it

```java
public final class SequenceFlagKernel {
    static final class Cell { int payload; int seq; }
    private static final VarHandle SEQ;

    private static Outcome run(SeqStore store, SeqLoad load, Runnable readBarrier) {
        // writer: for i in 1..=UPDATE_COUNT: store(2i-1); payload = f(i); store(2i);
        // reader: s1 = load(); if even: payload = read; readBarrier(); s2 = load();
        //         if s1 == s2: trust payload, else retry
    }
}
```

This dataset's `run()` helper is shared by all five variants — they
differ only in `store`/`load`/`readBarrier`. Building this lab's own
implementation surfaced two genuine, reproduced-on-real-hardware bugs
(Apple M1 Max/ARM), both worth reading in full because they are the
concrete version of this lab's theory, not hypothetical:

1. **A read-barrier is required between the payload read and the second
   seq re-check, independent of the seq accesses' own ordering.** An
   early version without it failed intermittently for *every* variant,
   including the fully sequentially-consistent one — proof this was a
   structural gap in the double-check pattern, not one variant's bug.
   Fix: `loadLoadFence()` (or, in the acquire/release and CAS variants,
   the equivalent) between the payload read and the second seq load.

2. **The fence-based variant needed more than release/acquire.** Per the
   ordering/fence composition table in theory.md, `releaseFence()` and
   `acquireFence()` never supply a StoreLoad edge, and this seqlock's
   double-check genuinely depends on one. Two escalations were tried and
   empirically verified (repeated full-suite runs, not a single pass)
   before shipping: first, using `Opaque` instead of plain access for the
   `seq` field — necessary, but not sufficient. Second, replacing the
   read-barrier with a full fence — sufficient in isolation, but a
   from-scratch plain/fence combination for this *specific* field still
   proved unreliable under repeated whole-suite runs on this toolchain.
   The shipped `fenceBased()` therefore keeps the proven
   `setRelease`/`getAcquire` access-mode backbone (identical to
   `acquireReleasePublication()`) and layers one additional explicit
   `fullFence()` on publish — a real, verifiable "belt-and-suspenders"
   technique some seqlock/RCU implementations use, rather than a fully
   from-scratch fence implementation this lab could not itself verify as
   reliable. The full javadoc on `SequenceFlagKernel.fenceBased()` in the
   source has the complete reproduction writeup, including which
   combinations were tried and rejected.

The lesson this lab draws from its own construction: "correct per the
fence-composition rules" and "verified correct on this JVM/CPU" are
different claims, and this is exactly the caution behind the design
guardrail "no claim that absence of observed failure proves a
memory-order algorithm correct" — applied here to the *building* of the
lab, not only to its results.

## Counter update — why a stronger field is not an atomic RMW

```java
public static Result volatileSeqCstPublication() throws InterruptedException {
    Counter c = new Counter();
    Runnable work = () -> {
        for (int i = 0; i < MemOrdFixtures.COUNTER_INCREMENTS_PER_THREAD; i++) {
            int cur = (int) VALUE.getVolatile(c);
            VALUE.setVolatile(c, cur + 1); // two separate ops, not one atomic RMW
        }
    };
    // 4 threads run `work` concurrently; totals are lost under contention
}
```

`acquireReleasePublication()` uses `VALUE.getAndAddRelease(c, 1)` — a
genuine atomic RMW — and matches `COUNTER_EXPECTED_TOTAL` exactly, every
run. `casLoop()` retries a compare-and-set until it succeeds, tracking
`failedCas` per thread. `fenceBased()` uses a hand-rolled test-and-CAS
spinlock (`LOCK.compareAndSet`) guarding a *plain* increment — correct
via mutual exclusion, a genuinely different mechanism from the other
three. `plainBrokenPublication()` and `volatileSeqCstPublication()` are
both **deliberately not asserted** to hit the expected total in the
correctness suite; their observed totals are printed, not gated, because
the exact magnitude of lost updates under contention is real but not
reproducible run-to-run.

## Correctness tests

`MemOrdOperationsTest` asserts, for every dataset: the four
"correct" variants produce the exact expected outcome across the fixture
trial count, every time; the deliberately-weak variants complete without
hanging and their observed (not asserted) rates are printed. Run with:

```sh
cd content/labs/memory-ordering-atomics/code/java && mvn test
```

## JMH benchmark

Each dataset/variant pair is wired as its own JMH `@Benchmark` method
(single-threaded microbenchmark for the counter dataset's per-thread
cost; a `@Group`-based two-thread benchmark for the mailbox/seqlock
datasets' producer/consumer shape, following the same `@Group` pattern as
this site's [SPSC Ring Buffer](/lab/spsc-ring-buffer/) and
[False Sharing](/lab/false-sharing/) Java benchmarks). Setup (fixture
constant selection, thread/array allocation) happens outside the timed
region; JVM flags and allocation profiling follow this repository's
standard JMH conventions (see benchmark.md for the exact run commands).

jcstress is not wired into this repository's toolchain (see theory.md's
"Required visualization: jcstress/loom outcome matrix" for why this
lab does not introduce it) — the correctness evidence for this lab's
JMM claims comes from the stress-trial suite above, run at the trial
counts in `code/fixtures/`, not from an exhaustive jcstress interleaving
enumeration.

The runnable Maven/JMH project (with correctness tests under
`src/test/java/`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/memory-ordering-atomics/code/java" rel="noopener"><code>content/labs/memory-ordering-atomics/code/java/</code></a>
in this site's repository.
