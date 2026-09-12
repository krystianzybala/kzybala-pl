# Inlining and call-site shape — Rust track

Crate `inlining_lab`: the same shared strategy pool, enum alternative and
five variants as the Java side — with one deliberate, documented
mechanism difference. No `unsafe` anywhere in this crate.

## The pieces

- **`LongStrategy`** (trait) with six unit structs (`AddOne`, `MulThree`,
  `XorMask`, `ShiftAddSeven`, `SubEleven`, `ShiftOrOne`) — the same six
  transforms as the Java side, called through `Box<dyn LongStrategy>`.
- **`StrategyKind`** — the manual `match`-dispatch alternative: a `Copy`
  enum with an inherent `apply` method, compiled to a jump table or a
  compare chain, with no vtable at all.
- **`add_one_never_inline`** — the `oversizedCallee` counterpart. Rust
  exposes "never inline this" as an explicit `#[inline(never)]`
  attribute — a deterministic compiler *hint* — rather than an implicit
  size-threshold heuristic the way HotSpot's inliner works; the Java side
  has to pad a method's bytecode past a numeric budget to get the same
  effect (java.md). Both approaches produce the identical, documented
  outcome (never inlined); the *mechanism* for forcing it differs, which
  is itself worth noticing.
- **The strategy pool is shared across all three datasets** — same
  design choice as the Java side, because the variable under test is
  call-site shape, not per-dataset business logic.

## The `dyn Trait` vs. Java interface-call difference (read this before comparing numbers)

Every `dyn LongStrategy` call in this crate — monomorphic, bimorphic and
megamorphic alike — is compiled as a full vtable dispatch. LLVM makes
that decision once, at compile time; there is no runtime type profile,
no speculative inlining, and no deoptimization if the mix of concrete
types changes. This is structurally different from Java's `LongStrategy`
interface call, whose C2 dispatch strategy is decided *per call site, at
runtime,* from an observed type history. If this crate's mono/bimorphic/
megamorphic variants still show a measurable cost gradient, the most
likely cause is the CPU's own indirect-branch predictor doing worse as
target diversity grows — an architectural effect present in both
languages once a call falls back to a vtable — not LLVM choosing to
inline the monomorphic case and not the others. See `src/lib.rs`'s
crate-level doc comment and this lab's "comparing trait object to Java
sealed dispatch as identical" trap (benchmark.md): never attribute a
Rust-side gradient to compiler inlining without the assembly evidence
(`cargo asm`) to support it.

## Build, correctness gate, run

```bash
cd content/labs/inlining-call-site-shape/code/rust

# correctness gate — monomorphic==oversizedCallee, megamorphic==switchDispatch
cargo test

# lints and formatting
cargo clippy --all-targets
cargo fmt --check

# dev smoke — Criterion, pricing-functions dataset
cargo bench --bench inlining

# one cell of the publication matrix, unpinned dev run
cargo run --release --bin inlining_evidence -- \
  --variant megamorphic --dataset codecStrategies --seconds 2 --warmup-seconds 1

# LLVM optimization remarks / assembly inspection (capability-detected;
# confirms whether a given call site was actually devirtualized — see
# benchmark.md for what this host can produce)
cargo asm --release --bin inlining_evidence run 2>&1 | grep -iE "call|jmp" | head -20 || true
```

## Cross-language parity notes

- Rust's structural answer to "I want the monomorphic case free" is
  generics/monomorphization (a separate compiled function per concrete
  type, chosen at compile time), not `dyn Trait` — this lab deliberately
  uses `dyn Trait` throughout because it is the direct structural analog
  of Java's interface dispatch, and documents the resulting mechanism gap
  rather than reaching for generics to make the numbers look more similar
  to Java's.
- No cross-harness ranking is published between JMH and Criterion; the
  native-Linux evidence runner treats Java as the measured side for this
  lab's publication numbers (benchmark.md), and Rust's Criterion/
  `inlining_evidence` runs are a separately disclosed instrument.
