# Escape analysis and scalar replacement — Rust track

Crate `escape_analysis_lab`: the same five usage-pattern variants as the
Java side, applied to a plain (unboxed) `Aggregate` value type — plus one
supplementary function, `sum_boxed`, that is deliberately **not** part of
the cross-language correctness matrix. No `unsafe` anywhere in this
crate.

## The pieces

- **`Aggregate`** — a `Copy` struct (`x`, `y`), the same two-field shape
  as the Java `record`, but with none of the "does this need a heap
  slot" question a JVM object carries.
- **`AggregateHolder`** — the `storedIntoField` variant's target, storing
  an `Aggregate` **by value** (a plain copy, not a reference) into a
  field.
- **`opaque_sum`/`opaque_build`** — `#[inline(never)]`, Rust's
  deterministic counterpart to the Java side's `@CompilerControl
  (DONT_INLINE)`. Because `Aggregate` crosses these calls **by value**
  (never a pointer), the call still costs nothing beyond the call itself
  — there is no heap object whose escape needs proving on the Rust side.
- **`sum_identity_observed`** takes the local's address
  (`&a as *const Aggregate as usize`) through `black_box`, discarding the
  address itself — Rust's closest analog to observing identity. Note
  precisely what this does *not* do: force a heap allocation. At most it
  forces a stack slot, which is the entire point of this lab's
  cross-language contrast.
- **`sum_boxed`** — an explicit, genuine heap allocation
  (`Box::new(Aggregate{..})`) per element. This is the ONE function in
  this crate that is directly comparable to Java's *always-materialized*
  variants; it is excluded from the fixture-checked correctness matrix
  (which mirrors Java's five variants exactly) and presented separately,
  on purpose.

## Why none of the five matrix variants allocate — and why they still aren't free

Rust never heap-allocates a plain value unless you ask it to
(`Box::new`, `Vec`, `Rc`, …); there is no analysis proving a stack value
"can" avoid the heap, because the heap was never the default. All five
matrix variants here are therefore expected to allocate nothing —
verified directly (java.md's Java-side allocation profiling has no exact
counterpart on stable Rust, but the absence of any `Box`/`Vec`/heap API
in every matrix function is the structural guarantee). That does **not**
mean the five cost the same: development wiring runs on this
repository's machine showed `returnedObject` and `passedToOpaqueCall`
costing roughly 3–4× `nonEscaping`/`storedIntoField` — not from
allocation, but from `#[inline(never)]` call-boundary overhead, a lost
optimization opportunity rather than a heap cost. `sum_boxed`, measured
the same run, landed close to `identityObserved`'s cost and clearly below
`passedToOpaqueCall`'s — a genuine allocation in Rust, with no GC
attached, can cost less than a missed inlining opportunity. None of these
specific numbers are published evidence (dev-only); the qualitative
lesson — "no allocation ≠ free, and Rust's real allocation cost is not
Java's" — is what benchmark.md's real evidence is checked against.

## Build, correctness gate, run

```bash
cd content/labs/escape-analysis-scalar-replacement/code/rust

# correctness gate — all five variants agree on every dataset's total
cargo test

# lints and formatting
cargo clippy --all-targets
cargo fmt --check

# dev smoke — Criterion, coordinate dataset, five variants + sum_boxed
cargo bench --bench escape_analysis

# one cell of the publication matrix, unpinned dev run
cargo run --release --bin escape_analysis_evidence -- \
  --variant storedIntoField --dataset resultWrapper --seconds 2 --warmup-seconds 1
```

## Cross-language parity notes

- This lab's fourth named trap — "comparing stack Rust value to heap
  Java object with different semantics" — is precisely the mistake of
  reading Rust's five (non-allocating) matrix numbers next to Java's five
  (mostly-allocating) numbers as if they measured the same thing. They
  measure the same *usage pattern*, applied to two languages with
  opposite default answers to "does a small value need a heap address."
  `sum_boxed` is the one function that actually asks Rust the same
  question Java asks by default, and is the only Rust number this lab
  ever positions next to Java's materialized variants.
- No cross-harness ranking is published between JMH and Criterion; the
  native-Linux evidence runner treats Java as the measured side for this
  lab's publication numbers (benchmark.md), and Rust's Criterion/
  `escape_analysis_evidence` runs are a separately disclosed instrument.
