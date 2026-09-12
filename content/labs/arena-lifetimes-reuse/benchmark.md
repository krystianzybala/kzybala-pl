# Arena lifetimes, pools and reuse — benchmark methodology

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist, but no canonical evidence from the dedicated native-Linux
benchmark host has been imported for this laboratory yet — no verified
performance conclusion is available. Development-machine runs validate
wiring and correctness only and are never published as measured results
(`docs/measurement-environments.md`).

## What is deterministic vs what is measured

- **Deterministic** — every variant's checksum over a given dataset's
  identical value stream: lifecycle strategy must never change the
  result (`code/fixtures/arena-lifetimes-reuse-fixtures.json`).
- **Measured** — B/op, allocations/op, reset/close cost, RSS
  high-water mark, contention and p99, for each of the 15
  (variant × dataset) cells, only from the native-Linux evidence runner.

## Operation definitions (the contract)

| Measured artifact | One "operation" | Oracle |
|---|---|---|
| `fullPass` (AverageTime) | one complete pass over the declared dataset | the variant's checksum matches the fixture-pinned value for the declared dataset |
| `oneBatchLatency` (SampleTime) | one batch's worth of work (500 messages / 100 documents / 200 buffer-ops) | same, evaluated over a batch-sized slice |

`EV_SELECTOR` names the class only, so JMH runs BOTH `@Benchmark`
methods for every (variant, dataset) cell
(`scripts/performance-lab/labs/arena-lifetimes-reuse.conf`) —
`fullPass` gives B/op and allocations/op via JMH's `-prof gc`;
`oneBatchLatency` gives this lab's required p99 directly from JMH's own
SampleTime percentile reporting, scoped to one batch so
`batchArena`'s reset/close cost is the dominant per-sample cost rather
than diluted across 1,000 batches. Java is the measured side for this
lab's publication numbers; the Rust track builds an equivalent evidence
binary and runs it separately (rust.md) — no cross-harness ranking is
published.

## Required metrics

B/op and allocations/op (JMH `-prof gc`'s `gc.alloc.rate.norm` on
`fullPass`), reset/close cost (`batchArena`'s share of
`oneBatchLatency`'s tail percentiles), RSS high-water mark and
contention (`perf stat` wrapping the pinned worker process,
`scripts/performance-lab/labs/arena-lifetimes-reuse.conf`,
`LAB_PERF_EVENTS`), p99 (`oneBatchLatency`'s own `p0.99` output).

## Profiler evidence

JMH's `-prof gc` is the primary, always-available allocation-rate
evidence — it is what revealed this lab's central finding (java.md).
`async-profiler`'s alloc and lock profiles supplement it where the host
supports them, specifically for isolating the pool variants' lock-wait
time from their allocation cost. A heap profiler is recorded as
unavailable rather than substituted where the host does not support it.

## A real, humbling finding from development wiring (dev-only, never published)

Running `-prof gc` on this repository's development machine showed
`allocatePerItem` allocating essentially nothing (≈1.6 B/op over a full
500,000-message pass) — the JIT scalar-replaced the tiny scratch object
away, confirming content/labs/escape-analysis-scalar-replacement's
mechanism empirically rather than by assertion. `globalPool`, meant to
REDUCE allocation, instead allocated ≈12,000,135 B/op — its
`ConcurrentLinkedDeque` backing allocates a linked-list node on every
push/pop, and those nodes cost far more than the scalar-replaced object
they were pooling. The practical result: `globalPool` ran ≈83× slower
than `threadLocalReuse`; `boundedPool` avoided the allocation problem
(≈87 B/op) but was still ≈53× slower, from lock overhead alone.
Running the Rust evidence binary showed the SAME final verdict —
pooling loses badly — reached via a DIFFERENT mechanism: Rust's
`allocate_per_item` and `thread_local_reuse` did NOT converge the way
Java's did (a real ≈3× gap, since Rust has no scalar replacement to
eliminate the baseline allocation), and Rust's two pools (sharing one
`Mutex<Vec<T>>` implementation) were both ≈57× slower than
`thread_local_reuse` from lock overhead alone. None of these exact
numbers are published evidence; the directions and mechanisms — and the
fact that the SAME conclusion holds in both languages for DIFFERENT
underlying reasons — are what this lab's real, reproduced evidence is
checked against.

## Raw data and reproduction

```bash
# Correctness gates (both languages) — run before trusting any timing:
cd content/labs/arena-lifetimes-reuse/code/java && mvn test
cd content/labs/arena-lifetimes-reuse/code/rust && cargo test

# Dev wiring checks (never publication-eligible):
mvn -q -DskipTests package
java -jar target/benchmarks.jar ArenaReuseBenchmark.fullPass -p variant=globalPool -prof gc
cargo run --release --bin arena_lifetimes_reuse_evidence -- \
  --variant globalPool --dataset messageBatches

# Publication evidence (dedicated native-Linux host only; see
# docs/linux-evidence-runner.md for host setup and CPU selection):
./scripts/performance-lab/run-linux-evidence.sh arena-lifetimes-reuse \
  --profile publication --cpus <CPU_A>

# Focused single-variant rerun (diagnosis):
./scripts/performance-lab/run-linux-evidence.sh arena-lifetimes-reuse \
  --profile smoke --cpus <CPU_A> --variant bounded-trees
```

Raw JMH/Criterion output, perf stat CSVs, placement evidence and
environment metadata are produced per variant by the runner and imported
through the canonical result pipeline — numbers are never transcribed
into this page by hand.

## Limitations

- "unbounded pools" is a named trap this lab's `globalPool` variant
  implements literally, not just describes — its own measured allocation
  behavior (≈12 MB per full pass, from linked-node churn) is the direct,
  concrete evidence for the trap, not an abstract warning.
- "cross-thread ownership ambiguity" is a named trap: every variant in
  this lab's matrix has an unambiguous, single-writer-at-a-time ownership
  contract for its scratch memory — the pool variants' `borrow`/`release`
  pairing is the only place ownership crosses a boundary, and it is
  always complete (borrowed, then released) within one operation.
- "pooling tiny objects already scalar-replaced" is a named trap this
  lab's own Java-track evidence demonstrates directly, not hypothetically
  (java.md) — this is arguably this lab's most important, most concrete
  finding.
- "excluding cleanup cost" is a named trap: `batchArena`'s `close()` is
  always inside the measured region, in both languages, for both
  `fullPass` and `oneBatchLatency` — never excluded as "setup."
- No result on this page is a Java-versus-Rust performance claim
  (non-goal by policy); both languages reach the identical qualitative
  verdict (pooling loses here) through different, disclosed mechanisms,
  never merged into a single winner claim.
