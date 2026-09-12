# Virtual Threads vs Platform Threads vs Event Loops — benchmark methodology

<div class="disclosure measured">
  <p class="disclosure-kind">Illustrative development run</p>
  <p>These preliminary values were captured on an Apple M1 Max developer
  workstation under uncontrolled desktop conditions. They are retained only
  to illustrate the likely shape of the effect and are not
  publication-grade performance evidence. Canonical results for this
  laboratory are collected separately on the dedicated native-Linux
  benchmark host with explicit CPU placement, environment capture,
  correctness gates, independent JVM forks, and profiler evidence where
  required (<code>docs/measurement-environments.md</code>).</p>
  <p>Run details: JMH 1.37, OpenJDK 26.0.1 (HotSpot), Apple M1 Max (10
  cores: 8P + 2E), macOS 26.6.2, arm64. Rust: Criterion 0.5.1, same
  machine, reduced sample size (10) for a fast smoke pass. Ordinary
  desktop load alongside, no CPU affinity pinning, no control over
  performance- vs. efficiency-core scheduling.</p>
</div>

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist, but no canonical evidence from the dedicated native-Linux
benchmark host has been imported for this laboratory yet — no verified
performance conclusion is available, and the development numbers below are
not a substitute.

## Method

Both languages define one benchmark operation per variant over the same
200-task list, with dataset generation and validation outside the timed
region. **Java's virtual-thread and Rust's "high-fan-out" scenarios are not
directly comparable**: Rust has no lightweight thread runtime, so its
`high_fanout_os_threads` scenario reuses the plain OS-thread mechanism —
this is a documented design trade-off (per the semantic-equivalence
contract), never presented as a Java-vs-Rust speed result. Every other
variant (platform-per-request, fixed event loop, CPU-bound pool, mixed) is
mechanistically equivalent between the two languages: one thread/task
model, one shared task list, one pure checksum function.

## Illustrative development data (this run — not a portable claim)

**Java (JMH, `Mode.AverageTime`, ms/op, lower is better, 200-task `simulatedSocketWait` unless noted):**

| Benchmark | Time |
|---|---|
| `platformPerRequest_socketWait` | 10.670 ms/op |
| `virtualPerTask_socketWait` | 3.124 ms/op |
| `fixedEventLoop_socketWait` | 3.166 ms/op |
| `mixed_socketWait` | 3.670 ms/op |
| `cpuBoundPool_shortCpu` | 0.739 ms/op |
| `fixedEventLoop_longCpu` | 77.445 ms/op |

**Rust (Criterion, ms/op unless noted, median of 10 samples):**

| Benchmark | Time |
|---|---|
| `thread_per_request_socket_wait` | 4.945 ms |
| `high_fanout_os_threads_socket_wait` | 5.052 ms |
| `fixed_event_loop_socket_wait` | 3.091 ms |
| `cpu_bound_pool_short_cpu` | 244.3 µs |
| `fixed_event_loop_long_cpu` | 74.98 ms |
| `mixed_socket_wait` | 5.179 ms |

## What this shows

**`platformPerRequest_socketWait` is clearly the outlier in Java** (10.67
ms/op vs. 3.1–3.7 ms/op for every other variant) — creating and tearing
down 200 real platform threads for a workload that mostly just waits 2ms
each carries real OS-thread-creation overhead that virtual threads, the
event loop and the mixed variant all avoid. This is the mechanism the
theory page describes: platform threads are not "wrong," but their
per-thread cost dominates once fan-out gets high on a mostly-blocking
workload.

**Rust shows no equivalent outlier** (`thread_per_request` and
`high_fanout_os_threads` land within noise of each other, both ~5 ms) —
expected, since both are the *same* OS-thread mechanism in this crate; the
absence of a gap here is not evidence that "Rust threads are as fast as
Java virtual threads," it is evidence that these two Rust scenarios
measure the same thing, which is exactly why they are excluded from
cross-language comparison.

**`fixedEventLoop_longCpu` is dramatically slower than everything else in
both languages** (77.4 ms in Java, 75.0 ms in Rust) — 200 tasks × a long
CPU stage, executed serially on one loop thread, is the predicted
mechanism: an event loop never blocks on I/O, but it has exactly one
thread available for CPU work, so a long CPU stage there is paid
sequentially, 200 times over, with zero parallelism. This number is the
clearest illustration in this lab of "an event loop trades many cheap
waiters for exactly one worker."

**Neither language's numbers should be read as a portable claim about
virtual threads vs. event loops in general** — this is 200 tasks, 2ms
simulated waits, on one uncontrolled development machine. The *shape*
(platform-per-request overhead at fan-out, event-loop CPU-stage
serialization) is the mechanism worth trusting; the exact milliseconds are
not.

## Raw data and reproduction

```sh
# Correctness gate — run before trusting any timing:
node scripts/benchmark-platform/run-correctness-gate.js virtual-platform-event-loop

# Smoke run (wiring check only — zero statistical value):
cd content/labs/virtual-platform-event-loop/code/java && mvn -q -DskipTests package && \
  java -jar target/benchmarks.jar -f 1 -wi 1 -i 2 -r 500ms -w 500ms
cd content/labs/virtual-platform-event-loop/code/rust && cargo bench -- --noplot
```

The runnable projects are committed at `code/java/` (Maven + JMH) and
`code/rust/` (Cargo + Criterion) next to this file. JMH emits per-iteration
raw samples (`-rf json`); Criterion writes
`target/criterion/**/new/raw.csv` and a generated HTML report. Re-run on
your own hardware — thread scheduling, core topology and OS thread limits
all change this curve.
