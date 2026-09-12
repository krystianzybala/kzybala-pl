# Deoptimization and uncommon traps — Rust track

Crate `deopt_lab`: the same five variants and shift constants as the Java
side, mirrored for an identical, fixture-pinned correctness total — and
the `deopt_timeline` binary, the direct counterpart of Java's
`DeoptTimelineHarness`. No `unsafe` anywhere in this crate.

## The pieces

- **`TypeA`/`TypeB`/`TypeC`** (implementing `Strategy`, called through
  `dyn Strategy`) — the same three transforms as the Java side.
- **`sum_rare_exception_path`** uses a `Result<i64, &'static str>`
  cold path — deliberately never a `panic!`/unwind, because there is no
  recompilation cycle in Rust for a panic-based demonstration to be
  evidence *of*.
- **`sum_nullability_shift`** uses `Option<i64>` — checked, always, by
  the type system at every use, with no speculative non-null assumption
  to violate.
- **`deopt_timeline`** — the same batched-latency-timeline harness as
  Java's `DeoptTimelineHarness`: fixed 100-element batches, the same
  pre-shift / shift-window / post-shift split.

## Why every variant here is expected to be flat — and what was actually measured

There is no runtime deoptimization mechanism in Rust: every `dyn
Strategy` call's dispatch cost is fixed at compile time, LLVM never
watches a call site's runtime type history, and there is therefore no
assumption for a later type, error, or `None` to invalidate. Running
`deopt_timeline` on this repository's development machine confirmed this
directly: `shiftWindow.maxNs` stayed in the same 100–300 ns range across
**all five** variants — including `profileShiftAfterWarmup` (167 ns) and
`lateSubtypeLoading` (167 ns), whose Java counterparts spiked to 49,042 ns
and 233,375 ns respectively in the identical measurement window. This
flatness is this lab's central cross-language finding, not a missing
effect or a harness bug — see `src/lib.rs`'s crate-level doc comment for
the mechanism, and never read a flat Rust timeline as "Rust failed to
reproduce the Java result": it succeeded at reproducing the *absence* of
a mechanism it structurally does not have.

## Build, correctness gate, run

```bash
cd content/labs/deoptimization-uncommon-traps/code/rust

# correctness gate — all five variants agree with the fixture-pinned totals
cargo test

# lints and formatting
cargo clippy --all-targets
cargo fmt --check

# dev smoke — aggregate throughput, wiring/correctness check
cargo bench --bench deopt

# THE evidence tool — the actual latency timeline
cargo run --release --bin deopt_timeline -- \
  --variant profileShiftAfterWarmup --dataset strategyDispatch
```

## Cross-language parity notes

- This lab's "equating Rust branch misprediction with JVM
  deoptimization" trap is precisely the mistake of expecting
  `deopt_timeline`'s numbers to show a spike because *some* CPU-level
  effect (branch misprediction, cache behavior) must surely show up
  somewhere. It does not, at the granularity this lab measures, because
  none of the five variants' dispatch decisions depend on a runtime
  profile in the first place — there is no analog of "the JIT guessed
  wrong" when nothing was ever guessed.
- No cross-harness ranking is published between JMH and Criterion (or
  between `DeoptTimelineHarness` and `deopt_timeline`); the native-Linux
  evidence runner treats Java as the measured side for this lab's
  publication numbers (benchmark.md), and Rust's runs are a separately
  disclosed instrument whose entire point is contrast, not competition.
