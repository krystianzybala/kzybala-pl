# False sharing — benchmark methodology

<div class="disclosure measured">
  <p class="disclosure-kind">Measured</p>
  <p>JMH 1.37, OpenJDK 26.0.1 (HotSpot), Apple M1 Max (10 cores, no SMT,
  single socket), macOS, arm64. Rust: Criterion 0.5, rustc 1.88.0, same
  machine, pinned release profile. Profile: the repository's
  <code>full</code> profile (JMH: 2 forks, 3×1 s warmup + 5×1 s measurement;
  Criterion: 3 s warm-up, 5 s measurement, 100 samples). Single developer
  machine — not a dedicated, thermally-stable benchmark rig; treat absolute
  numbers as illustrative of the effect's <em>shape</em>, not a portable
  performance claim. Raw harness output, environment metadata and the exact
  commands are preserved in the run artifact
  <code>results/false-sharing/99a8cb189d18d569/</code> and were imported
  through the canonical result schema
  (<code>imported-canonical-records.json</code> in the same directory) —
  no number in this file was typed from memory.</p>
</div>

## Semantic equivalence contract

Before comparing anything across languages, this is what the compared
variants do and don't share:

- **Inputs:** no external input. Each writer thread performs a fixed count
  of increments on its own dedicated counter; parameters (thread count,
  increment count) are identical across languages and recorded in the
  shared fixture `code/fixtures/false-sharing-fixtures.json`.
- **Output and correctness:** after joining the writers, every counter must
  equal its exact expected value (no lost updates). Both languages assert
  the same fixture cases (`CounterCorrectnessTest.java`, `src/lib.rs`
  tests).
- **Overflow:** counters are 64-bit unsigned-equivalent counts far below
  overflow; wrap-around semantics are never exercised.
- **Allocation/lifetime:** counter state is allocated once per measured
  iteration, outside the timed increment loops' steady state; no allocation
  occurs inside a timed increment.
- **Threading and memory order — the intentional difference:** Java's
  shared/padded/contended variants use `volatile` increments (a
  sequentially-consistent store on each write); Rust's shared/padded use
  `fetch_add(Relaxed)` (an atomic RMW with no ordering guarantee). The
  sharded variant uses the same plain-read + release-write / acquire-read
  protocol in both languages. **Because the ordering contracts differ,
  Java-vs-Rust absolute numbers in this lab are a design-trade-off
  illustration, not a language speed comparison** — the valid comparisons
  are *within* a language, across layouts, where ordering is held constant.
- **Setup/teardown:** thread spawn/join is inside the measured region for
  Criterion (per-batch) and outside it for JMH (persistent group threads) —
  another reason cross-harness absolute numbers are not directly
  comparable; each harness's numbers are internally consistent.

## Experiment matrix

**Variants** (all measured in this run):

| Variant | Java | Rust |
|---|---|---|
| Adjacent counters (the bug) | `SharedCounters`, volatile | `SharedCounters`, `fetch_add(Relaxed)` |
| Manually padded counters | `PaddedCounters`, 56-byte field padding | `PaddedCounters`, `#[repr(align(64))]` |
| Runtime-assisted padding | `ContendedCounters`, `@Contended` + `-XX:-RestrictContended` | not applicable — Rust has no runtime layout assist; `repr(align)` *is* the compiler-enforced equivalent, so this cell is intentionally not double-counted |
| Per-thread shard + reduction | `ShardedCounters`, VarHandle release/acquire | `ShardedCounters`, release store/acquire load |

**Thread placement profiles:**

| Profile | Status on this host |
|---|---|
| Two threads (OS-scheduled) | measured (this run) |
| Physical-core sweep (1..N pinned cores) | **unsupported on this host** — macOS provides no public thread-to-core affinity API; the OS scheduler places threads. Reproduce on Linux with `taskset`/`pthread_setaffinity_np`. |
| SMT sibling placement | **not applicable on this host** — Apple M1 Max has no SMT. On an SMT x86 host, pin both writers to one core's siblings and the false-sharing gap collapses (shared L1). |
| Cross-socket placement | **not applicable on this host** — single-socket part. On multi-socket hosts expect a substantially larger shared-layout penalty. |

**Metrics:**

| Metric | Status on this host |
|---|---|
| ops/s (throughput) | measured — JMH `thrpt` (reported as ops/ms) |
| ns/update | measured — Criterion time per 100,000-increment batch (ns/batch ÷ 100,000 per-thread updates) |
| Cache-to-cache transfers | **evidence unavailable on this host** — requires `perf c2c` (Linux). Not fabricated; see the evidence-tools table. |
| LLC misses | **evidence unavailable on this host** — requires `perf stat` (Linux). |
| CPU utilization | observed qualitatively (all writer threads at ~100% in every variant — false sharing does not show up as idle time, which is exactly why counters, not utilization, are needed to see it). Not captured as a per-run artifact on this host. |

**Evidence tools:**

| Tool | Availability |
|---|---|
| JMH group benchmarks | used (raw `-rf json` in the run artifact) |
| Criterion threaded harness | used (raw `estimates.json`/`sample.json` in the run artifact) |
| `perf c2c` | unavailable (Linux-only; macOS host). The lab page's evidence panel shows the *shape* of a HITM report and how to read one; capturing a real one on Linux is exercise 3's follow-up. |
| `perf stat` | unavailable (Linux-only; macOS host) |
| async-profiler | available on macOS in principle; not captured for this run — CPU-time profiles do not attribute coherence stalls on this platform, so it adds little here. Marked absent in the imported records. |

## Reproduction commands

```sh
# 1. Correctness gate — must pass before any timing is trusted:
node scripts/benchmark-platform/run-correctness-gate.js false-sharing

# 2. Smoke (wiring check only — zero statistical value):
cd content/labs/false-sharing/code/java && mvn -q -DskipTests package && \
  java -jar target/benchmarks.jar -f 1 -wi 0 -i 1 -r 200ms -w 200ms
cd content/labs/false-sharing/code/rust && cargo bench --bench false_sharing -- --quick --noplot

# 3. Full profile (what this file's tables show) — create the immutable run
#    artifact first, then point the harnesses' raw output at it:
node scripts/benchmark-platform/write-run-artifact.js false-sharing full
java -jar target/benchmarks.jar -f 2 -wi 3 -w 1s -i 5 -r 1s -rf json -rff <run-dir>/raw/jmh-full.json
cargo bench --bench false_sharing -- --warm-up-time 3 --measurement-time 5 --sample-size 100 --noplot

# 4. Publication profile: only on a controlled host —
#    see docs/benchmark-publication-procedure.md.
```

## Measured data (full profile, run `99a8cb189d18d569` — not a portable claim)

**Java (JMH, ops/ms, higher is better, 99.9% CI):**

| Layout | Throughput | CI |
|---|---|---|
| Shared counters | 69,735 | ±2,257 |
| Padded counters (manual) | 296,566 | ±51,379 |
| `@Contended` counters | 328,851 | ±506 |
| Per-thread shards + reduction | 871,538 | ±107,683 |

**Rust (Criterion, time per 100,000-increment batch, lower is better, median with 95% CI):**

| Layout | Median | CI |
|---|---|---|
| Adjacent atomics (shared line) | 975.1 µs | [959.0 µs, 1,003.9 µs] |
| `align(64)` counters (padded) | 261.0 µs | [260.5 µs, 262.2 µs] |
| Per-thread shards (release store) | 774.9 µs | [739.1 µs, 848.4 µs] |

## Conclusions

**Observation (what the data shows):** separating the two counters onto
different cache lines improved Java throughput ~4.3× (69,735 → 296,566
ops/ms) and cut Rust batch time ~3.7× (975 µs → 261 µs). `@Contended`
performed at least as well as manual padding (non-overlapping CIs, modest
margin). Java sharding was the fastest Java variant by ~2.9× over padding.
Rust sharding, however, was *slower* than Rust padding (775 µs vs 261 µs)
while still ~1.3× faster than the shared layout.

**Interpretation (mechanism-based):** the shared-vs-padded gap in both
languages, with ordering held constant within each language, is what the
hypothesis predicts — the cost was coherence traffic from co-located
independent counters, and layout alone removed it. The Java sharding win is
consistent with its cheaper per-write contract (plain read + release write,
no RMW). The Rust sharding slowdown relative to padding is consistent with
the release store (`stlr` on ARM64) costing more per operation than an
uncontended relaxed `LDADD` RMW on this microarchitecture: layout decides
whether cores fight over a line; ordering strength sets the per-operation
floor once they don't.

**Limitation:** one machine (no SMT, single socket, no affinity control),
one run set, OS-scheduled thread placement, and no hardware-counter
evidence on this host — the coherence-traffic interpretation rests on the
controlled layout variable plus the mechanism model, not on a direct
cache-to-cache transfer measurement here. The cross-language ordering
difference means Java-vs-Rust absolute numbers must not be read as a
language race (see the equivalence contract).

**Inference (plausible, not proven by this data):** the ~4× ratios will not
transfer to your hardware, but the *ordering* (shared slowest; separated
lines dramatically better) should hold on any multi-core cache-coherent
system; the Rust sharded-vs-padded ranking may well invert on x86-64, where
release stores are close to free — that is a prediction this lab's Linux
reproduction steps can falsify.

**Verification status:** these results are from a single full-profile run
set on an uncontrolled developer host and are labeled accordingly in the
imported records (evidence maturity below `verified`); they have not been
independently reproduced. Re-run the project in `code/` on your own
hardware and replace these tables with your own numbers before using either
the ratios or the absolute figures for a real design decision.

## Historical (pre-hardening) measurements

The lab's original 2026 run, captured before the correctness gate, run
artifacts and canonical import pipeline existed. These are the values
migrated into `legacy-results.json` and permanently labeled
`legacy-unprovenanced` in the data layer (no raw artifact survives). Kept
for transcription fidelity, not for comparison — same machine class, but a
different JMH/Criterion configuration than the current tables:

| Variant | Value |
|---|---|
| Java shared counters | 68,869 ops/ms (±4,506) |
| Java padded counters (manual) | 255,796 ops/ms (±32,251) |
| Java `@Contended` counters | 325,893 ops/ms (±7,616) |
| Rust adjacent atomics | 1.0017 ms per batch (median) |
| Rust `align(64)` counters | 263.44 µs per batch (median) |

## Known limitations

- Single machine, single full run set — no cross-run variance data beyond
  the reported CIs, and no CPU pinning is possible on this host.
- The padded-vs-`@Contended` comparison is directionally reliable, not
  precise to the percentage point.
- Criterion measures thread spawn/join inside the batch; JMH does not —
  cross-harness absolute values are not directly comparable.
- The pre-hardening (2026, earlier run) numbers formerly in this file are
  retained as historical records in `legacy-results.json` and remain
  labeled `legacy-unprovenanced` in the data layer.
- See `theory.md`'s "Limitations of this model" for how the interactive
  visualisation simplifies coherence-traffic timing relative to real
  hardware.

## Raw data and reproduction

The runnable benchmark projects are committed at `code/java/` (Maven + JMH)
and `code/rust/` (Cargo + Criterion) next to this file, each with a
`README.md` giving exact build/run commands. The full-profile run behind
the tables above is preserved unmodified — JMH `-rf json`, Criterion
`estimates.json`/`sample.json`/`tukey.json`, console logs and environment
manifest — under `results/false-sharing/99a8cb189d18d569/`, with
schema-validated canonical records in `imported-canonical-records.json`
alongside it. Inspect the raw samples, not only the summary line, before
drawing conclusions from a run.
