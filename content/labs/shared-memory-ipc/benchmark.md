# Shared-memory IPC — benchmark methodology

<div class="disclosure measured">
  <p class="disclosure-kind">Illustrative development run</p>
  <p>These preliminary values were captured on an Apple M1 Max developer
  workstation under uncontrolled desktop conditions. They are retained only
  to illustrate the likely shape of the effect and are not
  publication-grade performance evidence. Canonical results for this
  laboratory are collected separately on the dedicated native-Linux
  benchmark host with explicit CPU placement, environment capture,
  correctness gates, independent JVM forks, and profiler evidence where
  required (<code>docs/measurement-environments.md</code>). The
  process-restart/recovery scenario's "fault/recovery time" is illustrative
  wall-clock timing around one specific restart sequence in this lab's
  correctness suite, never a statistically characterized distribution — see
  theory.md's Assumptions and scope.</p>
  <p>Run details: JMH 1.37, OpenJDK 22 (HotSpot), Apple M1 Max, macOS,
  arm64, loopback TCP. Java: 1 fork, 1 warmup + 2 measurement iterations of
  500ms each (a wiring smoke, not a controlled measurement),
  <code>Mode.AverageTime</code>. Rust: Criterion 0.5.1, default sampling
  (Criterion itself reduced/extended the effective sample count per
  benchmark and reported this explicitly), same machine,
  <code>[profile.bench] inherits = "release"</code>.</p>
</div>

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist — including a real cross-process integration test in both
languages (`ProcessLauncherTest`/`process_launcher.rs`) — but no canonical
evidence from the dedicated native-Linux benchmark host has been imported
for this laboratory yet. No verified performance conclusion is available,
and the development numbers below are not a substitute.

## Operation definitions

**Java:** one benchmark operation is exactly one full run of 2,000
messages for one variant (`socketBaseline`, `sharedMemoryCopyPayload`,
`sharedMemorySlotView`, `sharedMemoryBatched`) at one payload-size
profile, with the shared segment/socket setup performed in `@Setup`,
outside the timed region. The shared-memory variants here use two
independent mappings of the same backing file within the benchmark
process — see java.md for why this is distinct from, and does not
substitute for, the real cross-process proof in `ProcessLauncherTest`.

**Rust:** the identical unit of work — one full run of 2,000 messages per
variant per profile — with setup performed once outside `b.iter(...)`,
matching the Java definition's setup/timing split.

## What this shows (development run only)

**Java, `medium128Byte` profile, µs for one full 2,000-message run (lower is better):**

| Benchmark | Time |
|---|---|
| `socketBaseline` | 2008.7 |
| `sharedMemoryCopyPayload` | 387.1 |
| `sharedMemorySlotView` | 378.2 |
| `sharedMemoryBatched` | 353.4 |

**Rust, `medium128` profile, Criterion point estimate for one full 2,000-message run (lower is better):**

| Benchmark | Time |
|---|---|
| `socket_baseline_medium128` | 2050.9 µs |
| `shared_memory_copy_payload_medium128` | 276.9 µs |
| `shared_memory_slot_view_medium128` | 273.6 µs |

**The socket baseline is roughly 5-7x slower than every shared-memory
variant in both languages, in this run** — consistent with theory.md's
prediction: every message on the socket path crosses the kernel network
stack (even over loopback, this is a real syscall and copy path), while
the shared-memory variants only cross a memory-mapped region already
established before timing began. This gap is large enough to be unlikely
to be pure noise, but this is still one development-machine run.

**The zero-copy slot view is only marginally faster than the copying
variant in both languages, in this run** — a message this small (128
bytes) makes the copy itself cheap relative to the cursor/publication
overhead shared by both variants; theory.md would predict a larger gap
for the 1KiB profile, where the copy cost is a bigger fraction of the
total. This dev run does not include the 1KiB numbers in this table —
re-run the reproduction commands below for the full profile sweep.

**Batching shows a modest additional improvement over the copying
variant in Java, in this run** — consistent with amortizing the
`writerSeq` release-store across 8 messages instead of paying it once per
message, though the effect here is smaller than the copy-vs-socket gap.

**Java and Rust numbers must not be compared directly against each
other** — different JVM/JIT warm-up regime vs. Rust's ahead-of-time
compiled release binary, and Java's smoke profile used deliberately short
warm-up specifically to keep the wiring check fast. Real cross-language
framing (if any) belongs to the native-Linux canonical run.

## Raw data and reproduction

```sh
# Correctness gate — run before trusting any timing (includes the real
# cross-process integration tests, which spawn actual child processes):
cd content/labs/shared-memory-ipc/code/java && mvn test
cd content/labs/shared-memory-ipc/code/rust && cargo test --release

# Smoke run (wiring check only — zero statistical value):
cd content/labs/shared-memory-ipc/code/java && mvn -q -DskipTests package && \
  java -jar target/benchmarks.jar -f 1 -wi 0 -i 1 -r 200ms -w 200ms
cd content/labs/shared-memory-ipc/code/rust && cargo bench

# Manual cross-process run (two separate terminals), for direct observation:
java -cp code/java/target/classes pl.kzybala.lab.shmipc.ProducerMain /tmp/ring.bin 256 100000 128
java -cp code/java/target/classes pl.kzybala.lab.shmipc.ConsumerMain /tmp/ring.bin 100000 128
```

The runnable projects are committed at `code/java/` (Maven + JMH +
`ProducerMain`/`ConsumerMain`) and `code/rust/` (Cargo + Criterion +
`producer`/`consumer` binaries) next to this file. JMH emits per-iteration
raw samples (`-rf json`); Criterion writes `target/criterion/**/new/raw.csv`
and a generated HTML report. Re-run on your own hardware — loopback
network stack behavior, page size, and CPU topology all change this curve.
