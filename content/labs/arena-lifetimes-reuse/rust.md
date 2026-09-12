# Arena lifetimes, pools and reuse — Rust track

Crate `arena_lifetimes_reuse_lab`: `message_batches` /
`parse_trees` / `scratch_buffers` modules (five lifecycle variants
each), and `UnboundedPool<T>` / `BoundedPool<T>` (`Mutex<Vec<T>>`-backed
pools every dataset's `global_pool`/`bounded_pool` functions share).

## No scalar replacement means the same source shape means something different

- **`allocate_per_item`'s `Box::new` is always a real `malloc` call.**
  Rust has no JIT and no escape analysis — there is no optimization pass
  that can prove a `Box<MessageScratch>` never needs to be heap-allocated
  and eliminate the call. This is the single most important structural
  difference from the Java track, and this crate's own measured evidence
  confirms it directly (see below): `allocate_per_item` and
  `thread_local_reuse` do NOT converge in Rust the way they do in Java,
  because in Rust there is a real cost for reuse to actually save.
- **`batch_arena` is a `Vec<MessageScratch>` allocated once per batch,
  dropped once at batch end.** This is Rust's natural analog to "one
  arena per batch, closed at batch end": the whole batch's backing
  allocation is freed in a single `Drop`, not once per element — the
  same amortization idea as Java's `Arena.ofConfined()`, achieved with
  an ordinary, safe `Vec` rather than a dedicated arena API, because
  Rust's ownership model already gives every `Vec` this "region"
  property for free.
- **`thread_local_reuse` is an ordinary reused local — no thread-local
  storage API needed.** A `let mut m = MessageScratch::default();`
  declared once outside the loop is ALREADY confined to the calling
  thread by ordinary Rust ownership; there is nothing extra to opt into
  for a single-threaded pass. (A genuinely multi-threaded version would
  reach for `std::thread_local!`, matching Java's `ThreadLocal` more
  literally — this crate's benchmarks are single-threaded per worker,
  matching the Java track's own per-cell measurement contract.)
- **`UnboundedPool`/`BoundedPool` both use `Mutex<Vec<T>>` — the
  difference is only whether `release` checks capacity.** Unlike Java's
  two structurally different pool implementations
  (`ConcurrentLinkedDeque` vs. `ArrayBlockingQueue`), this crate's two
  pools share the identical underlying data structure and locking
  mechanism; only the bound check differs. This is a real, disclosed
  asymmetry with the Java track (rust.md's own cross-language parity
  note, below) — it means Rust's `global_pool` and `bounded_pool` are
  expected to cost nearly the same, unlike Java's, where the pool
  implementations themselves differ structurally.

## What was actually measured (dev-only, never published)

Running the evidence binary across all five `messageBatches` variants on
this repository's development machine (release build) confirmed the
Rust-specific version of this lab's central finding: `allocate_per_item`
(≈0.96 ns/message) and `thread_local_reuse` (≈0.32 ns/message) did
**not** converge — `thread_local_reuse` was a real, measurable ≈3×
faster, because in Rust (unlike Java) `allocate_per_item`'s `Box::new`
calls are genuine, uneliminated heap allocations that reuse genuinely
avoids. `batch_arena` (≈1.06 ns/message) landed close to
`allocate_per_item`, consistent with a per-batch `Vec` allocation
amortizing similarly to per-item `Box` allocation at this batch size.
`global_pool` and `bounded_pool` were nearly identical to each other
(≈18.28 ns/message each) — expected, since both share the same
`Mutex<Vec<T>>` implementation — and both were dramatically slower than
`thread_local_reuse` (≈57× slower), confirming that `Mutex` lock
acquisition alone, even single-threaded and uncontended, is real,
measurable overhead that outweighs the allocation-avoidance benefit
pooling is meant to provide. This is the SAME final verdict as the Java
track (pooling loses badly here) reached by a DIFFERENT mechanism: Java's
loss comes from a naive pool allocating more than an already-scalar-
replaced object needed; Rust's loss comes from lock overhead outweighing
a real, but smaller, allocation-avoidance benefit that simple reuse
already captures without any locking at all. None of these exact numbers
are published evidence; the directions and mechanisms are what
benchmark.md's real, reproduced evidence is checked against.

## Build, correctness gate, run

```bash
cd content/labs/arena-lifetimes-reuse/code/rust

# correctness gate — every variant sums to the identical total per dataset
cargo test

# lints and formatting
cargo clippy --all-targets
cargo fmt --check

# dev/wiring Criterion bench (five variants, messageBatches)
cargo bench

# THE evidence tool — run directly, any variant/dataset
cargo run --release --bin arena_lifetimes_reuse_evidence -- \
  --variant boundedPool --dataset temporaryParseTrees
```

## Cross-language parity notes

- `allocate_per_item` vs. `thread_local_reuse` NOT converging in Rust
  (unlike Java) is this lab's central Rust-track finding — it is not a
  claim that Rust is "worse" at avoiding allocation, but a direct,
  disclosed consequence of Rust having no escape-analysis-driven scalar
  replacement to begin with (theory.md).
- Rust's `global_pool`/`bounded_pool` sharing one underlying
  `Mutex<Vec<T>>` implementation (vs. Java's two structurally different
  pool classes) is a real asymmetry in this lab's cross-language
  comparison, disclosed here rather than hidden — it means the
  Java-track finding that `globalPool` specifically over-allocates (via
  `ConcurrentLinkedDeque`'s linked nodes) has no direct Rust analog;
  Rust's two pools differ only in the bound check, not in allocation
  behavior.
- No cross-harness ranking is published between the JMH benchmarks and
  `arena_lifetimes_reuse_evidence`; the native-Linux evidence runner
  treats Java as the measured side for this lab's publication numbers
  (benchmark.md), and Rust's runs are a separately disclosed instrument.
