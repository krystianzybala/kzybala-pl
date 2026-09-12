# Bounds checks and loop shape — Rust track

Crate `bounds_checks_lab`: the same deterministic backing-array
generation and five variants as the Java side. The only `unsafe` in this
crate is isolated in the `unchecked` module — everything else, including
the `opaqueLimit` variant's optimization barrier, is safe Rust.

## The pieces

- **`build_backing`** / **`random_permutation`** — mirror the Java
  methods field-for-field; the permutation is the byte-for-byte same
  Fisher-Yates construction already pinned in
  content/labs/cache-locality-working-set.
- **`opaque_len`** / **`opaque_usize`** — `#[inline(never)]` plus
  `std::hint::black_box` around the return value: Rust's direct
  counterpart to the Java side's `@CompilerControl(DONT_INLINE)`, an
  explicit barrier so LLVM cannot prove the returned bound relates to the
  slice's own length.
- **`slice_canonical`/`slice_opaque_limit`/`slice_safe_iterator`** build a
  genuine, zero-copy `&[i32]` via `&backing[start..end]` — Rust's borrow
  checker guarantees this view can never outlive `backing` and never
  aliases mutably, with no runtime cost beyond the one range check that
  creates the slice. See java.md's equivalence-contract note: Java has no
  matching view type for a primitive array.
- **`strided_safe_iterator`** uses `.iter().step_by(stride)` — a built-in,
  safe iterator adapter for exactly this access pattern; the Java side
  has no native strided-array iterator and falls back to `IntStream`
  (java.md).
- **`unchecked::sum_unchecked`** — the crate's only `unsafe fn`, with an
  explicit `# Safety` doc comment stating the precondition (every index
  read must be `< backing.len()`) and no validation of its own: isolation
  and documentation, not just an escape hatch (this lab's "using unsafe
  as the default answer" and "out-of-bounds UB in Rust" traps,
  benchmark.md).
- **`benches/bounds_checks.rs`** (Criterion, dev/wiring) — the five
  variants over the primitive-arrays dataset, pinned release profile,
  `black_box` around every input.
- **`src/bin/bounds_checks_evidence.rs`** (publication) — the Rust
  counterpart of `BoundsChecksLinuxEvidenceBenchmark`: single pinned
  worker, the closed-form correctness oracle before any timing, one JSON
  document on stdout, non-zero exit on any correctness/placement
  violation.

## Build, correctness gate, run

```bash
cd content/labs/bounds-checks-loop-shape/code/rust

# correctness gate — all five variants agree on every dataset's total
cargo test

# lints and formatting (clippy::needless_range_loop is explicitly
# allowed on the canonical/opaqueLimit functions — an index loop is the
# point there, not an oversight; see the #[allow] comments in src/lib.rs)
cargo clippy --all-targets
cargo fmt --check

# dev smoke — Criterion, primitive-arrays dataset
cargo bench --bench bounds_checks

# one cell of the publication matrix, unpinned dev run
cargo run --release --bin bounds_checks_evidence -- \
  --variant unchecked --dataset stridedAccess --seconds 2 --warmup-seconds 1

# LLVM optimization remarks (capability-detected; requires nightly or
# -Cremark flags — see benchmark.md for what this host can produce)
cargo rustc --release --bin bounds_checks_evidence -- -Cremark=all 2>&1 | grep -i bounds || true
```

## Cross-language parity notes

- Rust's slice type gives the `slicesSubranges` dataset's canonical/
  opaque/safeIterator variants a genuine borrowed view with compiler-
  enforced lifetime and aliasing guarantees; Java's equivalent variants
  index the original array directly (java.md) — an intentional,
  documented language-capability difference, not a bug in either
  implementation.
- No cross-harness ranking is published between JMH and Criterion; the
  native-Linux evidence runner treats Java as the measured side for this
  lab's publication numbers (benchmark.md), and Rust's Criterion/
  `bounds_checks_evidence` runs are a separately disclosed instrument.
