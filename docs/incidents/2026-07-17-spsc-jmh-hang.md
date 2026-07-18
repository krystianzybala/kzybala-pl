# Incident: SPSC JMH benchmark hang (batch-20260717T150131Z)

**Status:** resolved (code + harness + infrastructure fixes landed; regression-tested)
**Affected batch:** `batch-20260717T150131Z` on the canonical measurement host — preserved as **diagnostics only**, never evidence.
**Lab:** spsc-ring-buffer, variant `cached-b1-c1024` (cursorMode=cached, batch=1, capacity=1024)

## What happened

During the publication batch, the SPSC lab's JMH evidence run stopped making
progress. A forked JMH worker JVM (`ForkedMain`) had been running for
**14h40m at 100% CPU** when it was found. The batch had no per-invocation
wall-clock bound, so nothing ever terminated it.

Thread dumps taken from the hung JVM showed:

- the **producer** thread `RUNNABLE`, spinning inside
  `SpscLinuxEvidenceBenchmark.produce` (the ring-full retry loop);
- the **consumer** thread `WAITING` on a `CountDownLatch` — JMH's
  end-of-iteration latch, i.e. the consumer had already left the measured
  loop and been parked by the harness.

## Root cause

The grouped benchmark methods used unbounded retry loops:

```java
// before (the hang): spins forever if the consumer never drains
while (!buffer.tryProduce(produceSeq)) {
    Thread.onSpinWait();
}
```

At the end of a measurement iteration JMH stops calling one group member
while the other may still be inside its method. With the ring **full** and
the consumer parked on the iteration latch, the producer's
`tryProduce` could never succeed and the loop had no exit condition. The
deadlock is structural, not probabilistic: any iteration boundary that
catches the ring full wedges the fork permanently.

A second, independent failure: the infrastructure trusted the benchmark to
terminate. No external timeout existed at the invocation, runner, or batch
level, so a wedged fork consumed the host indefinitely.

## Fixes

1. **Termination-safe benchmark methods** — both worker loops are bounded by
   JMH's `Control.stopMeasurement`; each worker can return regardless of
   what the other is doing (`SpscLinuxEvidenceBenchmark`).
2. **Explicit item accounting** — `@AuxCounters(EVENTS)` states publish
   `producedItems`, `consumedItems`, `producerFullRetries`,
   `consumerEmptyPolls`, `sequenceViolations`. JMH's invocation throughput
   is **never** item throughput; the counters are the authoritative item
   rates, and in-flight items at shutdown are bounded by ring capacity.
3. **Finite transfer harness** — `SpscTransferHarness` (exactly N items,
   start barrier, shared cancellation, internal deadline, bounded joins) is
   the primary end-to-end items/s source. It cannot hang by construction;
   every matrix case now has a `harness-*` aux variant next to its JMH
   cost-view variant.
4. **External hard timeouts** — every measurement invocation in the Linux
   evidence runner executes under `run_with_deadline` with a profile-derived
   wall-clock budget. On expiry: jcmd/thread-dump/affinity diagnostics are
   captured from the still-live tree, the tree gets SIGTERM (SIGKILL only
   after the grace period), the run is stamped `failed-benchmark-timeout`,
   and the runner exits 3.
5. **Batch abort** — the batch orchestrator treats runner exit 3 as
   `failed-benchmark-timeout`, quarantines the run under `failed-runs/`, and
   aborts the whole batch. A hang is never silently skipped.
6. **Signal hygiene** — both the runner and the orchestrator carry
   EXIT/INT/TERM traps that terminate benchmark children (SIGTERM first);
   no benchmark JVM can outlive its runner.
7. **Focused reruns** — `--variant <name>` reruns a single matrix variant
   (recorded as `focused:<name>` in the manifest), so the defect-revealing
   case can be re-verified cheaply without the full matrix.

## Regression tests

- `SpscTerminationTest` (4): stopped producer/consumer return on full/empty
  ring; independent termination; in-process grouped JMH matrix smoke with
  counter-coherence assertions.
- `SpscTransferHarnessTest` (4): exact N-item transfer across the matrix;
  deadline expiry stops both workers; injected consumer fault cancels the
  producer; tiny-ring batched exactness.
- `scripts/test-linux-evidence.js` (+10): `run_with_deadline` pass-through
  and 124-on-hang with diagnostics and no survivors; full-runner hang
  classified `failed-benchmark-timeout` (exit 3) with pre-termination
  diagnostics, SIGTERM-only cleanup, and no further variants; `--variant`
  validation and focused-manifest recording; SPSC two-view matrix
  (core/sweep) invariants.
- `scripts/test-benchmark-batch.js` (+1): runner exit 3 aborts the entire
  batch with the failing lab named and the run quarantined.

## Disposition of the failed batch

`batch-20260717T150131Z` artifacts (including the thread dumps) are
preserved on the measurement host as diagnostics only. Nothing from that
batch is imported, compared, or published. The SPSC lab must be re-measured
with the fixed benchmark before any evidence claim.

## Lessons

- A grouped JMH benchmark must never contain a loop whose exit depends on
  the other group member making progress; bound every retry loop with
  `Control.stopMeasurement`.
- Throughput of *invocations* is not throughput of *items*; publish item
  counters explicitly.
- Infrastructure must assume benchmarks can hang: hard external timeouts
  with diagnostics-before-kill, and batch-level abort, are part of the
  measurement contract, not optional hardening.
