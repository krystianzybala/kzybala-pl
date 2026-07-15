# False sharing — exercises

Three exercises, one per skill this lab teaches: diagnosing the mechanism,
fixing it with code, and reading the hardware's own evidence. Hints and
solutions are collapsed — attempt each exercise before opening them, and
verify against the stated success criteria, not against intuition.

## Exercise 1 — Diagnosis (a deliberately flawed variant)

A colleague "reproduces" this lab on their machine and concludes false
sharing is a myth. Their setup:

```java
@State(Scope.Thread)               // <- each benchmark thread gets its own instance
@BenchmarkMode(Mode.Throughput)
public class MyFalseSharingTest {
    long counterA;
    long p1, p2, p3, p4, p5, p6, p7;
    long counterB;

    @Benchmark public void writeA() { counterA++; }
    @Benchmark public void writeB() { counterB++; }
}
```

They ran `java -jar benchmarks.jar MyFalseSharingTest -t 2` and measured no
difference with or without the padding fields.

**Task:** identify every reason this benchmark cannot observe false sharing,
regardless of padding. There are at least three independent flaws.

**Success criteria:** you can name the flaws, explain *why* each one hides
the effect (which line is or isn't shared, which threads write it), and
state the minimal fix for each.

<details>
<summary>Hint</summary>

Look at (1) what `Scope.Thread` does to the object instances, (2) which
*fields* the two plain `@Benchmark` methods write and from which threads
JMH will call them, and (3) whether `counterA`/`counterB` are even
guaranteed to be written concurrently in this configuration.

</details>

<details>
<summary>Solution</summary>

1. **`Scope.Thread` gives every thread its own instance.** Thread 1's
   `counterA` and thread 2's `counterB` live in different objects, usually
   on different lines — nothing is shared, falsely or otherwise. Padding
   local objects that are not adjacent changes nothing (trap #2 in
   `theory.md`). Fix: `@State(Scope.Group)` with `@Group` methods, as in the
   lab benchmark.
2. **Without `@Group`, JMH runs `writeA` and `writeB` as separate
   benchmarks**, each measured in its own run — the two counters are never
   written concurrently in the same measurement. `-t 2` runs two threads
   *of the same method*, so both threads increment `counterA` of their own
   instances. Fix: group the two methods so one thread runs each,
   concurrently, against one shared state object.
3. **Plain (non-`volatile`) `long` fields in a tight `counter++` loop let
   the JIT keep the counter in a register** and write it back rarely — the
   loop may never generate per-increment line traffic at all. Fix:
   `volatile` fields (as the lab uses) or `VarHandle` release writes, kept
   *identical across the compared variants*.
4. (Bonus) Even fixed, thread *placement* is uncontrolled — on an SMT
   machine the two threads can land on siblings sharing an L1, muting the
   effect (trap #1). State or control topology before trusting the number.

</details>

## Exercise 2 — Implementation (measurable success criteria)

The lab's `ShardedCounters` fixes write-side false sharing between shards,
but a "status" struct like this one is still broken:

```rust
#[repr(C)]
pub struct WorkerStatus {
    pub tasks_completed: AtomicU64,   // written ~1M times/s by worker thread
    pub last_heartbeat_ms: AtomicU64, // written 1×/s by worker thread
    pub shutdown_requested: AtomicU64,// written once, ever, by the main thread
}
// Vec<WorkerStatus>, one entry per worker, workers iterate neighbors' shutdown flags
```

**Task:** restructure `WorkerStatus` (and/or its container) so that no hot
write by one worker can invalidate a line another thread reads or writes,
*without* padding every field.

**Success criteria (measure, don't assert):**

1. Your version passes the same correctness expectations (counts are exact
   after join).
2. In a two-worker Criterion benchmark modeled on this lab's harness, your
   version's `tasks_completed` throughput is within noise of a
   single-worker run (the lab's padded/sharded variants show what "within
   noise" looks like on your machine).
3. `size_of::<YourStatus>()` grew by less than a full cache line per field —
   i.e. you separated *by write frequency and writer*, not by padding all
   three fields.

<details>
<summary>Hint</summary>

Group fields by (writer thread, write rate): `tasks_completed` is hot and
worker-owned; `last_heartbeat_ms` is cold and worker-owned; and
`shutdown_requested` is written once by *a different thread* but read hot.
Hot worker-owned data wants a line per worker (that is exactly
`CacheLineAligned`); cold fields of *different* workers can share a line
with each other far more cheaply than with any hot field.

</details>

<details>
<summary>Solution sketch</summary>

Split the struct by writer and temperature, then give only the hot array
per-entry alignment:

```rust
pub struct HotCounters {                       // one line per worker
    counters: Vec<CacheLineAligned<AtomicU64>>,
}
pub struct ColdStatus {                        // heartbeats: all workers may share lines
    heartbeats: Vec<AtomicU64>,
}
pub struct Control {                           // written once; read-shared is fine —
    shutdown: AtomicBool,                      // after the single write it settles into
}                                              // every reader's cache in Shared state
```

Reads of a rarely-written line are not false sharing (writes are what
invalidate); the heartbeat array trades a harmless once-per-second
invalidation for 64× less memory than padded entries. Verify criterion 2
by benchmarking `HotCounters` at 1 worker vs. 2 workers; verify criterion 3
with `std::mem::size_of` assertions in a test.

</details>

## Exercise 3 — Evidence interpretation (raw counter output)

Below is a **synthetic teaching example** in `perf stat`'s output format —
constructed for this exercise, not captured from any run. It is educational
material for practicing counter interpretation only: it is never used as
measurement evidence, never supports this lab's performance conclusions,
and never enters a comparison or maturity calculation. (The lab's real
hardware-counter evidence comes exclusively from the native-Linux evidence
runner — `scripts/performance-lab/run-linux-evidence.sh` — and is imported
with full provenance; see `benchmark.md`.) The counter *relationships* here
are modeled on the published signature of false sharing on x86-64; the
absolute values are illustrative only. Two runs of an adjacent-counters
benchmark, one shared layout, one padded, labels removed:

```
Run A:
    41,082,522,911      cycles
     8,109,483,004      instructions              #    0.20  insn per cycle
       512,341,997      L1-dcache-load-misses
       498,112,340      machine_clears.memory_ordering

Run B:
    40,899,301,882      cycles
    31,882,110,391      instructions              #    0.78  insn per cycle
        11,204,873      L1-dcache-load-misses
           142,367      machine_clears.memory_ordering
```

**Task:** decide which run is the shared layout, and justify it from at
least three separate counters. Then state one conclusion these numbers
**cannot** support.

**Success criteria:** your identification is correct, each cited counter's
reasoning mentions the mechanism (line invalidation → refetch), and your
"cannot conclude" statement is genuinely unsupported by this data rather
than merely cautious.

<details>
<summary>Hint</summary>

Both runs spent the same wall-clock cycles (same measurement window). What
differs is how much *work* fit into those cycles, and what the cores were
doing instead of retiring instructions.

</details>

<details>
<summary>Solution</summary>

**Run A is the shared layout.**

- `insn per cycle` 0.20 vs 0.78: same cycle budget, ~4× fewer instructions
  retired — cores in run A stalled waiting for something. IPC alone doesn't
  say *what*, which is why you need the next two counters.
- `L1-dcache-load-misses` ~46× higher in A: every increment's read of the
  counter line missed L1 because the other core's write had just
  invalidated it — the refetch-after-invalidation signature.
- `machine_clears.memory_ordering` ~3,500× higher in A: the pipeline
  repeatedly discarded speculative work when the line it was operating on
  was yanked away mid-flight — direct evidence of cross-core interference
  on in-flight memory operations, not just cold caches.

**What this data cannot support:** any statement about *which variable*
shared the line, or that padding *caused* run B's improvement — the labels
were removed, and nothing here shows layout at all; the attribution comes
from knowing what was run. It also cannot rank Java vs. Rust or estimate
the effect on any other machine: it is one workload on one host. (If you
want line-level attribution from hardware evidence, that is exactly what
`perf c2c`'s HITM report adds — see the evidence section in
`benchmark.md`.)

</details>
