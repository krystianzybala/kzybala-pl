# Safepoints and time to safepoint — Java track

Package `pl.kzybala.lab.safepoints`: fixed worker-pool constants
(`SafepointFixtures`), a real FFM downcall to `usleep`
(`NativeSleep`), and `SafepointHarness` — the real evidence tool for
this lab.

## The pieces

- **`SafepointFixtures`** — `TOTAL_ITERATIONS = 400,000,000`,
  `NUM_CHUNKS = 8`, `FLEET_SIZE = 16`, `IDLE_THREAD_COUNT = 300`. Every
  dataset sums the identical logical range `[0, TOTAL_ITERATIONS)`;
  partitioning it across workers never changes the total, because integer
  summation is partition-invariant.
- **`NativeSleep`** — a genuine FFM downcall to POSIX `usleep`, used both
  for the `nativeSleepDowncall` dataset (interleaved between chunks) and
  the `threadInNativeCall` variant (one thread parked there for the
  entire run).
- **`SafepointHarness` — the real evidence tool.** It starts a JFR
  `Recording` enabling `jdk.SafepointBegin`, `jdk.SafepointStateSynchronization`,
  `jdk.SafepointEnd` and `jdk.ExecuteVMOperation`, runs the worker pool
  for the requested (variant, dataset), stops the recording, and
  correlates every event sharing a `safepointId` into one "episode":
  `SafepointBegin.duration` **is** TTSP, `ExecuteVMOperation.duration`
  (where `safepoint=true`) is the operation time, `SafepointEnd.duration`
  is the resume phase. This is measured directly from the JVM's own
  record of what happened — never estimated from a wall-clock delta
  around a `System.gc()` call.
- **Worker construction per dataset** — `numericLoop`/`nativeSleepDowncall`:
  one chunked trigger worker; `threadFleet`: one chunked leader (worker 0,
  the only one that triggers safepoints) plus 15 background workers each
  summing their own disjoint sub-range in one continuous pass.
- **Variant modifiers** — `threadInNativeCall` adds one extra thread
  parked in `usleep`; `manyIdleThreads` adds 300 extra `Thread.sleep`
  threads; `allocationPressureTrigger` replaces the trigger worker's
  explicit `System.gc()` calls with rapid garbage allocation, so GC
  episodes occur organically rather than on a fixed schedule;
  `longLoopSparsePolls` runs the IDENTICAL Java code as `cooperativeLoop`
  — only the launching JVM flag (`-XX:-UseCountedLoopSafepoints`)
  differs (benchmark.md).

## A real finding from development wiring (dev-only, never published)

Running `SafepointHarness` on this repository's development machine
produced results directly consistent with this lab's mechanism: the
`threadFleet` dataset's TTSP p50 (40,667 ns) was roughly **9× the**
`numericLoop` dataset's (4,417 ns) with the identical `cooperativeLoop`
variant — more threads, more coordination. `manyIdleThreads` on
`numericLoop` showed a p99/max of 365,334 ns against a control p99 of
7,250 ns — a real, measurable per-thread handshake tax across 300 extra
sleeping threads. `threadInNativeCall`'s TTSP (p50 3,625 ns) was, if
anything, *lower* than the baseline (4,417 ns) — directly confirming
that a thread blocked in native code does not add to TTSP, correcting
rather than confirming the naive assumption. `allocationPressureTrigger`
captured only 5 safepoint episodes in the same run window (vs. 8–11 for
the explicit-trigger variants) with a dramatically smaller
`stoppedNanos` p50 (537,458 ns vs. ~5.7–6.3 ms) — consistent with
organically-triggered young-generation collections being smaller and
cheaper than the explicit `System.gc()` calls' typically full
collections. None of these exact numbers are published evidence; the
directions are what benchmark.md's real evidence is checked against.

## Build, correctness gate, run

```bash
cd content/labs/safepoints-ttsp/code/java

# correctness gate — every partitioning sums to the identical total
mvn test

# build
mvn -q -DskipTests package

# THE evidence tool — run directly, any dataset/variant
java -cp target/benchmarks.jar pl.kzybala.lab.safepoints.SafepointHarness \
  --variant manyIdleThreads --dataset threadFleet

# the longLoopSparsePolls variant differs ONLY by this launch flag —
# identical Java code, real JVM-level mechanism difference
java -XX:-UseCountedLoopSafepoints -cp target/benchmarks.jar \
  pl.kzybala.lab.safepoints.SafepointHarness --variant longLoopSparsePolls --dataset numericLoop

# safepoint/GC application-stopped-time logging (capability-detected;
# flag availability varies by JDK build — see benchmark.md)
java -Xlog:safepoint,gc+stop-time -cp target/benchmarks.jar \
  pl.kzybala.lab.safepoints.SafepointHarness --variant cooperativeLoop --dataset numericLoop 2>&1 | grep -i stop
```

There is no JMH benchmark in this lab — JMH's throughput/avgtime modes
have no way to represent a genuinely multi-threaded, JVM-coordinated
stop-the-world event; `SafepointHarness` is the tool for both dev
wiring and (via the native-Linux runner) publication evidence
(benchmark.md).

## Reading the results

- Always read `ttspNanos` and `stoppedNanos` separately — a variant with
  a large `stoppedNanos` but small `ttspNanos` is dominated by the VM
  operation itself, not coordination; the reverse is this lab's actual
  subject.
- Compare `threadFleet` against `numericLoop` on the **same variant**
  first — this isolates pure thread-count effects from anything else.
- `allocationPressureTrigger`'s `safepointEpisodes` count is expected to
  differ run-to-run and from the other variants' fixed count — this is
  the organic-triggering mechanism working as intended, not measurement
  noise to average away.
