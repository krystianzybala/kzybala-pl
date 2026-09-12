# UDP ingest, batching and packet loss — benchmark methodology

<div class="disclosure measured">
  <p class="disclosure-kind">Illustrative development run</p>
  <p>These preliminary values were captured on an Apple M1 Max developer
  workstation under uncontrolled desktop conditions, over UDP loopback
  (127.0.0.1) only. They are retained only to illustrate the likely shape
  of the effect and are not publication-grade performance evidence.
  Canonical results for this laboratory are collected separately on the
  dedicated native-Linux benchmark host with explicit CPU placement,
  environment capture, correctness gates, independent JVM forks, and
  profiler evidence where required (<code>docs/measurement-environments.md</code>).
  The "batched Linux receive" variant is capability-unavailable on every
  host this lab currently runs on, including the eventual native-Linux
  run — see theory.md's "Batched receive (recvmmsg)" section — and is
  never fabricated here.</p>
  <p>Run details: JMH 1.37, OpenJDK 22 (HotSpot), Apple M1 Max, macOS,
  arm64, loopback UDP. Java: 1 fork, 1 warmup + 1-2 measurement iterations
  (a wiring smoke, not a controlled measurement), <code>Mode.AverageTime</code>.
  Rust: Criterion 0.5.1, default sampling (100 samples), same machine,
  <code>[profile.bench] inherits = "release"</code>.</p>
</div>

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist, but no canonical evidence from the dedicated native-Linux
benchmark host has been imported for this laboratory yet. No verified
performance conclusion is available, and the development numbers below
are not a substitute.

## Operation definitions

**Java:** one benchmark operation is exactly one full run of 4,000
datagrams for one variant (`onePacketPerReceive`, `reusedDirectBuffer`,
`copyingHandoff`, `zeroCopyViewHandoff`) at one datagram-size profile,
with the load generator spawned on a background thread and any
setup performed in `@Setup`, outside the timed region.

**Rust:** the identical unit of work — one full run of 4,000 datagrams
per variant per profile — with the sender spawned on a background thread,
matching the Java definition's setup/timing split.

## What this shows (development run only)

**Java, `medium256Byte` profile, ms for one full 4,000-datagram run (lower is better):**

| Benchmark | Time |
|---|---|
| `reusedDirectBuffer` | 23.8-24.2 |
| `onePacketPerReceive` | 24.7-24.9 |
| `zeroCopyViewHandoff` | 28.3-30.2 |
| `copyingHandoff` | 29.5-30.5 |

**Rust, `medium256` profile, Criterion point estimate for one full 4,000-datagram run (lower is better):**

| Benchmark | Time |
|---|---|
| `zero_copy_view_handoff_medium256` | 45.4 ms |
| `one_packet_per_receive_medium256` | 46.4 ms |
| `reused_direct_buffer_medium256` | 46.6 ms |
| `copying_handoff_medium256` | 46.4 ms |

**The gap between the raw receive-loop variants (allocating vs. reused
buffer) is small in this run, in both languages** — at this message
count and payload size, the receive syscall and thread-scheduling
overhead dominate over one small per-packet allocation; theory.md's
prediction about allocation cost is more likely to show clearly at much
higher message rates or with profiler evidence (allocation counters),
which is exactly why this lab designates JFR allocation profiling as
required evidence rather than relying on wall-clock alone.

**The handoff variants (copying vs. zero-copy) are close to each other,
not dramatically different, in this run** — consistent with the same
observation: at 256-byte payloads and this message count, the fixed
per-message overhead of thread coordination (queue/channel operations)
is a larger fraction of the total than the one memory copy the zero-copy
variant removes. Theory.md would predict a larger, clearer gap at the
1400-byte profile, where the copy itself is bigger — this table does not
include that profile; re-run the reproduction commands below for the full
sweep.

**Java and Rust numbers must not be compared directly against each
other** — different JVM/JIT warm-up regime vs. Rust's ahead-of-time
compiled release binary, different OS scheduler behavior for the
background sender thread, and Java's smoke profile used deliberately
short warm-up specifically to keep the wiring check fast. Real
cross-language framing (if any) belongs to the native-Linux canonical
run.

## Raw data and reproduction

```sh
# Correctness gate — run before trusting any timing:
cd content/labs/udp-ingest-batching/code/java && mvn test
cd content/labs/udp-ingest-batching/code/rust && cargo test --release

# Smoke run (wiring check only — zero statistical value):
cd content/labs/udp-ingest-batching/code/java && mvn -q -DskipTests package && \
  java -jar target/benchmarks.jar -f 1 -wi 0 -i 1 -r 200ms -w 200ms
cd content/labs/udp-ingest-batching/code/rust && cargo bench
```

The runnable projects are committed at `code/java/` (Maven + JMH) and
`code/rust/` (Cargo + Criterion) next to this file. JMH emits
per-iteration raw samples (`-rf json`); Criterion writes
`target/criterion/**/new/raw.csv` and a generated HTML report. Re-run on
your own hardware — loopback network-stack behavior, OS scheduler, and
CPU topology all change this curve.
