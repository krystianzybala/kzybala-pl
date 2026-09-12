# Branch prediction and data distribution — Rust track

Crate `branch_prediction_lab`: the same deterministic dataset generation
and accumulation techniques as the Java side, over plain integer
arithmetic — no external numeric crate is needed since the mechanism
under test is the CPU front end, not a library. The library crate
(`src/lib.rs`) contains no `unsafe` code; the only `unsafe` in this lab is
the worker-affinity syscalls, isolated in the evidence binary.

## The pieces

- **Dataset generation** — `biased_values` and `biased_records` mirror
  the Java methods field-for-field, including deriving `kind` and
  `payload` from the same stream value per index. `xorshift64` operates on
  native `u64`, which is unsigned by construction — the same modulo
  arithmetic as Java's `Long.remainderUnsigned` calls, without needing to
  spell that out.
- **`sorted_ascending`** and **`sorted_by_kind`** reorder the exact
  `random5050` data — `sort_by_key` is a stable sort (Rust's documented
  guarantee), matching Java's `TimSort`-backed comparator sort so the
  cold-block/hot-block boundary lands on the identical index for both
  languages.
- **Two accumulation techniques** — `filtered_sum_branchy` (a real `if`,
  wrapping accumulation) and `filtered_sum_branchless` (the identical
  `(diff >>> 31) ^ 1` mask, computed through an explicit `i32`/`u32` cast
  pair so the bit pattern matches Java's 32-bit `>>>` exactly rather than
  Rust's default 64-bit arithmetic shift).
- **`benches/branch_prediction.rs`** (Criterion, dev/wiring) — the four
  variants over the byte-flags dataset, pinned release profile, explicit
  `black_box` around every input.
- **`src/bin/branch_evidence.rs`** (publication) — the Rust counterpart of
  `BranchPredictionLinuxEvidenceBenchmark`: single pinned worker via
  `sched_setaffinity`, the same permutation-invariance correctness oracle
  before any timing, fixed-duration measurement loop, one JSON document on
  stdout, non-zero exit on any correctness or placement violation.

## Build, correctness gate, run

```bash
cd content/labs/branch-prediction/code/rust

# correctness gate — every fixture cell in both variant axes
cargo test

# lints and formatting
cargo clippy --all-targets
cargo fmt --check

# dev smoke — Criterion, byte-flags dataset, all four variants
cargo bench --bench branch_prediction

# one cell of the publication matrix, unpinned dev run
cargo run --release --bin branch_evidence -- \
  --variant sorted --dataset mixedHotCold --seconds 2 --warmup-seconds 1
```

## Cross-language parity notes

- Rust's `u64` is unsigned by definition, so `x % 100` on the raw
  xorshift64 output needs no extra care; the Java side must call
  `Long.remainderUnsigned` explicitly to get the same unsigned modulo on
  a signed `long` — both resolve to the identical stream-to-value mapping,
  verified by the shared checksum fixture.
- The branchless mask is deliberately computed on a 32-bit lane
  (`as i32` / `as u32`) in both languages, not on the native 64-bit
  accumulator type, so the `>>> 31` / `>> 31` shift-by-sign-bit trick
  means the same thing in both — a correctness-relevant detail, not a
  stylistic one, and it is exactly what the fixture's
  `branchless_mask_never_produces_a_third_value` /
  `branchlessMaskNeverProducesAThirdValue` test pins down.
- No cross-harness ranking is published between JMH and Criterion
  (different instruments, different fork/warm-up models); the native-Linux
  evidence runner treats Java as the measured side for this lab's
  publication numbers (benchmark.md) and Rust's Criterion/`branch_evidence`
  runs are a separately disclosed instrument.
