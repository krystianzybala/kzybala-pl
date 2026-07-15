# Cache hierarchy — benchmark methodology

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
  cores: 8P + 2E), 64 GB unified memory, macOS 26.5.1, arm64. Rust:
  Criterion 0.5.1, rustc 1.88.0, same machine. Java: 1 fork, 5 warmup + 10
  measurement iterations of 1 second each, <code>Mode.AverageTime</code>.
  Rust: default Criterion sampling (100 samples, ~3–5 s target per
  benchmark). Ordinary desktop load alongside, no CPU affinity pinning, no
  control over performance- vs. efficiency-core scheduling (see "Known
  limitations").</p>
</div>

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist, but no canonical evidence from the dedicated native-Linux
benchmark host has been imported for this laboratory yet — no verified
performance conclusion is available, and the development numbers below are
not a substitute.

## Method

1. Each benchmark performs 1,000,000 dependent pointer-chase steps
   (`idx = next[idx]`) through a precomputed single-cycle table — see
   `java.md`/`rust.md` for why a dependent chain, built with Sattolo's
   algorithm for the random case, is used instead of a plain loop over an
   index array.
2. Four working-set/pattern combinations, identical in both languages down
   to the byte: `sequential`/`random` × `small` (2,048 elements × 8 bytes
   = 16,384 bytes) / `large` (16,777,216 elements × 8 bytes = 134,217,728
   bytes = 128 MiB). Element width is `long` (Java) / `u64` (Rust) in both
   — the same 8 bytes — and both languages build the exact same
   single-cycle permutation algorithm (Sattolo's) over the exact same
   sizes, so the two benchmarks measure the same access pattern over the
   same number of bytes with the same number of accesses.
3. Java reports the mean wall-clock time per 1,000,000-chase batch
   (`Mode.AverageTime`, JMH's default statistic) with its own 99.9%
   confidence interval. Rust/Criterion reports its own default statistic —
   a bootstrap-resampled *median* — with a confidence interval, plus an
   outlier-trimmed [min, max] observed range. **These are different
   statistics by each tool's own default, not a choice made for this lab**
   — see "Known limitations" for what this does and doesn't affect.
   Approximate per-access latency (ns) = batch time ÷ 1,000,000, shown
   alongside both tables below.

## Why the working sets fit/exceed cache: exact numbers, not "any consumer cache"

The small (16,384-byte) and large (134,217,728-byte) working sets were
sized against this specific machine's cache hierarchy, not a generic
guess. Apple M1 Max's cache sizes, per AnandTech's teardown and
[eclecticlight.co's Apple-silicon memory reference](https://eclecticlight.co/2024/03/06/apple-silicon-memory-and-internal-storage/):

| Level | Size | 16 KB working set | 128 MiB working set |
|---|---|---|---|
| P-core L1D | 128 KiB per core | 12.5% of capacity — fits | 1,024× larger — does not fit |
| E-core L1D | 64 KiB per core | 25% of capacity — fits | 2,048× larger — does not fit |
| P-cluster L2 (shared, 4 cores) | 12 MiB per cluster (×2 clusters) | fits trivially | ~10.9× larger — does not fit |
| E-cluster L2 (shared, 2 cores) | 4 MiB | fits trivially | ~32× larger — does not fit |
| System Level Cache (SLC, CPU+GPU) | 48 MiB | fits trivially | ~2.7× larger — does not fit |

The 16 KB working set fits inside the *smallest* L1D on the chip (the
64 KiB E-core cache) with room to spare, so "fits in L1" holds regardless
of which core type the scheduler happens to use. The 128 MiB working set
exceeds even the single largest cache level (the 48 MiB SLC) by close to
3×, and dwarfs any per-cluster L2 by an order of magnitude — "exceeds
cache" holds by a wide margin, not a narrow one.

## Illustrative development data (this run — not a portable claim)

**Java (JMH, mean time per 1,000,000-chase batch ± 99.9% CI, lower is better):**

| Benchmark | Mean ± CI | Median (for cross-tool comparison) | ≈ ns/access (mean) |
|---|---|---|---|
| `sequentialSmall` | 2,197.141 ± 5.120 µs | 2,197.927 µs | 2.20 |
| `randomSmall` | 2,201.602 ± 18.088 µs | 2,197.129 µs | 2.20 |
| `sequentialLarge` | 2,447.468 ± 365.860 µs | 2,361.393 µs | 2.45 |
| `randomLarge` | 118,539.850 ± 3,185.489 µs | 118,721.567 µs | 118.54 |

**Rust (Criterion, bootstrap median time per 1,000,000-chase batch, lower is better):**

| Benchmark | Median [CI] | Mean (for cross-tool comparison) | ≈ ns/access (median) |
|---|---|---|---|
| `sequential_small` | 1.2578 ms [1.2557, 1.2626] | 1.2720 ms | 1.26 |
| `random_small` | 1.2739 ms [1.2626, 1.2890] | 1.4172 ms | 1.27 |
| `sequential_large` | 1.2820 ms [1.2809, 1.2841] | 1.2844 ms | 1.28 |
| `random_large` | 110.9753 ms [110.4471, 111.7004] | 110.9466 ms | 110.98 |

## Reading these numbers

- **Small working set (fits L1):** sequential and random land within
  rounding of each other in both languages (Java: 2.20 vs. 2.20 ns/access;
  Rust: 1.26 vs. 1.27 ns/access median). Once the whole working set is
  cache-resident, *order stops mattering* — this matches scenarios 1 and 2
  in the interactive model above.
- **Large working set, random:** the two languages agree closely on the
  physically meaningful number — **118.54 ns/access (Java) vs. 110.98
  ns/access (Rust)**, about 7% apart, both squarely in the range of a
  plausible single uncontended DRAM access on this class of machine. This
  is the number that should generalize across languages on the same
  hardware, because it's dominated by the memory subsystem, not by either
  language's runtime.
- **Large working set, sequential:** nearly as fast as the small-working-set
  case in *both* languages — hardware prefetching at work, since a fixed
  +8-byte stride is exactly what a stream prefetcher is built to detect
  and stay ahead of.

**The sequential/random ratio is not directly comparable across
languages, and here is the concrete reason why.** Naively dividing
`randomLarge` by `sequentialLarge` gives ~48–50× in Java and ~86–87× in
Rust — a ~1.75× gap between the two "slowdown factors." That gap tracks
almost exactly the gap between the languages' own `sequentialSmall` /
`sequential_small` floors (Java ≈ 2.2 µs, Rust ≈ 1.26 µs per batch — a
1.75× difference), which is itself not a memory-hierarchy effect: a
16 KB, L1-resident chase should take a near-identical number of CPU
cycles regardless of language, so that floor is dominated by fixed
per-invocation harness overhead (JMH's blackhole consumption and
iteration bookkeeping vs. Criterion's leaner closure-call overhead), not
by memory latency. Because `sequentialLarge` is *also* mostly
prefetch-hidden (see above) it sits on roughly that same
overhead-dominated floor rather than reflecting real memory-subsystem
cost — so it inherits whichever language's floor is higher, and that
mechanically compresses or inflates the ratio computed against it. The
random-access absolute latency (the previous bullet) is the trustworthy,
cross-language number here; the ratio is a tooling artifact for the
*sequential* side of this specific comparison, not a hardware constant —
treat it as illustrative of "prefetching helps a lot," not as a precise
multiplier.

Re-run the project in `code/` on your own hardware (and against your own
cache sizes — see the investigation task in `theory.md`) before treating
any of the ratios or the absolute numbers as authoritative for a design
decision.

## Known limitations

- **No CPU affinity / core-type pinning.** Neither JMH nor Cargo/Criterion
  pins the benchmark process to a specific core, and macOS's thread-affinity
  API (`thread_policy_set` with `THREAD_AFFINITY_POLICY`) is documented by
  Apple as an advisory hint, not a hard pin, on Apple silicon — unlike
  Linux, there is no user-space equivalent of `taskset` available here.
  Results reflect whichever core(s) (performance or efficiency) and however
  much of them the OS scheduler actually granted during the run.
- **Concretely observed, not just a theoretical caveat.** An earlier
  attempt at this exact benchmark run, made while an unrelated browser
  process on this machine briefly used >100% CPU, produced a `randomSmall`
  result of 35,257 ± 61,833 µs/op (a mean *smaller than its own error
  bar*) with per-iteration raw samples ranging from 2,267 µs to 93,274 µs
  — a >40× swing within one ten-iteration measurement window, driven
  entirely by intermittent scheduling contention, not by the code under
  test. That run was discarded and is not reported above; the numbers
  published here are from a subsequent run, taken once system load had
  settled, with per-iteration variance checked and found consistent (see
  the investigation task for how to check this yourself). This is the
  concrete version of "single developer machine, not a dedicated rig":
  latency-bound (random-access) benchmarks are far more exposed to this
  kind of noise than throughput-bound (sequential/prefetched) ones,
  because a prefetched stream has slack to absorb a brief stall where a
  dependent random chase does not.
- **Bounds checks are not eliminated, in either language, and that's
  correct, not a bug.** `next[(int) idx]` (Java) and `next[idx as usize]`
  (Rust) both perform a normal array bounds check on every access, because
  `idx` is data-dependent (read from the array's own contents) rather than
  a loop-invariant range either compiler can statically prove safe —
  HotSpot's range-check elimination and LLVM's bounds-check elision both
  require exactly the kind of provable, loop-counter-derived index range
  this benchmark deliberately doesn't have. Disassembling the Rust `chase`
  function in isolation (`rustc -O --emit=asm`) confirms the check is
  present as a `cmp`/branch pair immediately before the load. This cost is
  small, branch-predicts well after the first iteration, and — the part
  that matters for validity — applies identically to every benchmark in
  both files, so it does not bias the sequential-vs-random comparison.
- **Rust's `for _ in 0..steps` adds no measurable iterator overhead.** The
  same disassembly shows the `Range<usize>` loop compiles to a plain
  decrement-and-branch pair, identical in shape to Java's C-style counted
  loop — there is no hidden iterator-protocol cost being masked here.
- **Criterion's stable-Rust `black_box` is a strong convention, not a
  compiler guarantee.** This project doesn't enable the nightly-only
  `real_blackbox` feature, so Criterion falls back to a
  `std::ptr::read_volatile` + `std::mem::forget` implementation — which
  the crate's own doc comment describes as possibly failing "to prevent
  code from being eliminated." The empirical disassembly evidence above
  (the load and its bounds check are present in the compiled output) is
  what this lab relies on, not the black-box comment alone. JMH's
  auto-detected compiler blackhole (visible in the console output as
  `Blackhole mode: compiler (auto-detected...)`) is a more mature,
  purpose-built mechanism by comparison.
- **Benchmarks were run sequentially, one language at a time**, specifically
  to avoid the two processes contending with each other for shared L2/SLC
  bandwidth and scheduler time — an earlier attempt at running both
  concurrently was caught and abandoned before any numbers from it were
  used.

## Raw data and reproduction

The runnable benchmark projects are committed at `code/java/` (Maven + JMH)
and `code/rust/` (Cargo + Criterion) next to this file. Each has a
`README.md` with exact build/run commands. JMH and Criterion both emit
per-iteration raw samples (JMH: `-rf json`; Criterion:
`target/criterion/**/new/sample.json`, `estimates.json`, and the generated
HTML report) — inspect those rather than only the summary line before
drawing conclusions from a run.
