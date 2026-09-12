# Memory-mapped files and page faults — benchmark methodology

<div class="disclosure measured">
  <p class="disclosure-kind">Illustrative development run</p>
  <p>These preliminary values were captured on an Apple M1 Max developer
  workstation under uncontrolled desktop conditions. They are retained only
  to illustrate the likely shape of the effect and are not
  publication-grade performance evidence. Canonical results for this
  laboratory are collected separately on the dedicated native-Linux
  benchmark host with explicit CPU placement, environment capture,
  correctness gates, independent JVM forks, and profiler evidence where
  required (<code>docs/measurement-environments.md</code>). In particular,
  genuine cold-vs-warm page-cache disambiguation via
  <code>posix_fadvise(POSIX_FADV_DONTNEED)</code> is only available on the
  native-Linux host — the numbers below use this repository's
  fresh-mapping approximation of "cold," not verified page-cache eviction
  (see theory.md's Assumptions and scope).</p>
  <p>Run details: JMH 1.37, OpenJDK 22 (HotSpot), Apple M1 Max, macOS,
  arm64, APFS filesystem. Java: 1 fork, 1 warmup + 2 measurement iterations
  of 500ms each (a wiring smoke, not a controlled measurement),
  <code>Mode.AverageTime</code>. Rust: Criterion 0.5.1, default sampling
  (100 samples where the target time allowed; Criterion itself reduced the
  effective sample count for the two slowest benchmarks and reported this
  explicitly), same machine, <code>[profile.bench] inherits = "release"</code>.</p>
</div>

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist, but no canonical evidence from the dedicated native-Linux
benchmark host has been imported for this laboratory yet — no verified
performance conclusion is available, and the development numbers below are
not a substitute.

## Operation definitions

**Java:** one benchmark operation is exactly one full pass over the
64 MiB / 1,048,576-record dataset for one variant (`bufferedRead`,
`warmMmapSequentialRead`, `coldMappedSequentialRead`, `randomMappedAccess`,
or `mappedWriteAndFlush`), with dataset creation and (for the warm
variant) the untimed warm-up pass performed in `@Setup`, outside the timed
region.

**Rust:** the identical unit of work — one full pass over the same
1,048,576-record dataset per variant — with the file written once outside
`b.iter(...)` and, for the warm variant, one untimed warm-up pass before
timing begins, matching the Java definition's setup/timing split.

## What this shows (development run only)

**Java, ms/op for one full 1,048,576-record pass (lower is better):**

| Benchmark | Time |
|---|---|
| `bufferedRead` | 551.6 |
| `randomMappedAccess` | 116.5 |
| `mappedWriteAndFlush` | 53.3 |
| `coldMappedSequentialRead` | 38.2 |
| `warmMmapSequentialRead` | 36.6 |

**Rust, ms/op for one full 1,048,576-record pass, Criterion point estimate (lower is better):**

| Benchmark | Time |
|---|---|
| `buffered_read` | 488.1 |
| `random_mapped_access` | 100.7 |
| `mapped_write_and_flush` | 27.3 |
| `cold_mapped_sequential_read` | 25.1 |
| `warm_mmap_sequential_read` | 22.1 |

**Buffered read is dramatically slower than every mmap variant in both
languages, in this run** — consistent with theory.md's prediction: reading
1,048,576 fixed-size records through `FileChannel.read`/`Read::read_exact`
issues roughly one explicit system call per record, while every mmap
variant issues none per record, resolving page faults transparently
instead. This gap is large enough (10-20x) that it is unlikely to be
purely measurement noise, but it is still one development-machine run —
the native-Linux run is what would make this a supported claim.

**Random access costs meaningfully more than sequential mapped access in
both languages, and less than buffered read** — consistent with the
page-level-locality mechanism theory.md describes: shuffled access defeats
the "one fault amortized over many records on the same page" benefit that
sequential access gets, without paying buffered read's full per-record
syscall cost either.

**"Cold" (freshly-mapped) and "warm" (reused mapping) sequential reads are
close to each other in this run, not dramatically different** — this is
the expected effect of the disclosed limitation above: an Apple M1 Max
under macOS with no page-cache-eviction capability keeps the underlying
file's pages resident in its own OS cache across "fresh" and "reused"
mappings alike, so this run cannot and does not demonstrate the true
cold-vs-warm gap theory.md describes; it demonstrates only the smaller
cost of establishing a fresh mapping and its first traversal. A real
cold-cache measurement requires the native-Linux host's `posix_fadvise`
capability.

**Java and Rust numbers must not be compared directly against each
other** — different filesystem (APFS vs the native host's filesystem when
that evidence lands), different JIT/warm-up regime vs. Rust's ahead-of-time
compiled release binary, and Java's smoke profile used deliberately short
warm-up specifically to keep the wiring check fast. Real cross-language
framing (if any) belongs to the native-Linux canonical run.

## Raw data and reproduction

```sh
# Correctness gate — run before trusting any timing:
cd content/labs/memory-mapped-files/code/java && mvn test
cd content/labs/memory-mapped-files/code/rust && cargo test --release

# Smoke run (wiring check only — zero statistical value):
cd content/labs/memory-mapped-files/code/java && mvn -q -DskipTests package && \
  java -jar target/benchmarks.jar -f 1 -wi 0 -i 1 -r 200ms -w 200ms
cd content/labs/memory-mapped-files/code/rust && cargo bench
```

The runnable projects are committed at `code/java/` (Maven + JMH) and
`code/rust/` (Cargo + Criterion) next to this file. JMH emits per-iteration
raw samples (`-rf json`); Criterion writes `target/criterion/**/new/raw.csv`
and a generated HTML report. Re-run on your own hardware — filesystem,
page-cache state, and storage medium all change this curve, often more
than CPU does for this specific mechanism.
