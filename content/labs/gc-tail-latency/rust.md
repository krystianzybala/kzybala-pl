# GC algorithms and tail latency — Rust track

Crate `gc_tail_lab`: the fixed constants and deterministic sum shared
with the Java side, and `gc_tail_harness` — a real, explicitly-built
evidence tool measuring what actually exists in Rust's allocator model,
since there is no collector, no live-set-driven pause and no safepoint
here to observe.

## The pieces

- **`gc_tail_lab`** — the identical `values_for`/`extra_size_for`/
  `expected_total` fixture generation as the Java side, plus the five
  variants (`sum_low_allocation_reuse`, `sum_steady_high_allocation`,
  `sum_bursty_allocation`, `sum_growing_live_set`,
  `sum_collector_matrix`), every one summing the identical
  fixture-pinned checksum.
- **`Node`** — `value: u64` plus a small `extra: Vec<u64>`, mirroring
  Java's `Node`; allocating variants heap-allocate it via `Box::new`.
- **`sum_growing_live_set`** returns both the checksum and the retained
  `Vec<Box<Node>>` — the caller (the evidence binary) owns dropping it,
  so the drop's cost can be measured as one explicit event rather than
  happening invisibly when the function returns.
- **`sum_collector_matrix`** — a documented pass-through, identical code
  to `sum_steady_high_allocation`. There is no collector to select in
  Rust; this variant exists only for matrix parity with the Java side and
  its numbers are never compared to Java's `collectorMatrix` results as
  if they measured the same lever.
- **`gc_tail_harness` — the real evidence tool.** For every variant that
  allocates, it wraps each `Box::new` in `Instant::now()`/`.elapsed()`
  and collects the resulting durations; `lowAllocationReuse` performs
  zero allocations in its loop, so its event count is zero — matching the
  Java track's `gcCount: 0` for the same variant. For `growingLiveSet`
  only, it additionally times the single, synchronous `drop` of the
  entire retained `Vec` after the loop completes.

## What this measures, and honestly, what it does not

**A real language-mechanism difference, not smoothed over** (this lab's
"calling Rust allocation-free by default" trap, benchmark.md): Rust has
no collector, no live-set-driven pause, and no GC pause distribution to
report. `allocNanos`' p50/p99/p999 here is per-allocation latency — how
long a single `Box::new` took — not a GC pause. `reclamationNanos` is one
deterministic event, not a percentile, because dropping a `Vec` happens
exactly once, synchronously, at a caller-chosen point, never as a
background interruption. Neither number should be read as "Rust's GC
pause" for comparison against the Java track's `pauseNanos` — there is
no GC, so there is no pause to compare. The honest comparison is
allocator-latency-tail vs. GC-pause-tail as two structurally different
mechanisms, never merged into one ranking (benchmark.md's
Java-versus-Rust prohibition).

## What was actually measured (dev-only, never published)

Running `gc_tail_harness` across all five variants on `objectGraphChurn`
on this repository's development machine (release build) showed
allocation latency itself extremely tight and flat: `steadyHighAllocation`,
`growingLiveSet` and `collectorMatrix` all showed p50 ≈ 41 ns, p99 ≈
42 ns — a single-digit-nanosecond spread between the median and the 99th
percentile, structurally unlike the Java track's millisecond-scale GC
pauses, because there is no coordinated stop-the-world operation for
this number to include. `growingLiveSet`'s p999/max (1,167 ns / 62,541
ns) were visibly higher than `steadyHighAllocation`'s (83 ns / 3,750 ns)
even though both allocate at the same rate — a real signal that a
growing heap does add *some* allocator-side cost in Rust too, just at a
scale roughly four orders of magnitude below a GC pause, not the "zero
cost, ever" strawman this lab's trap warns against. The one genuinely
large number in the whole run was `growingLiveSet`'s single
`reclamationNanos`: **≈28.4 ms** to drop one million retained, boxed
`Node`s — a real, synchronous cost, comparable in magnitude to the Java
track's `growingLiveSet` GC pauses (p99 ≈ 15.8 ms), but structurally
different: it happens exactly once, at a moment the code chose, not as a
recurring background pause interleaved with the workload. None of these
exact numbers are published evidence; the directions and mechanisms are
what benchmark.md's real, reproduced evidence is checked against.

## Build, correctness gate, run

```bash
cd content/labs/gc-tail-latency/code/rust

# correctness gate — every variant sums to the identical total per dataset
cargo test

# lints and formatting
cargo clippy --all-targets
cargo fmt --check

# dev/wiring Criterion bench (five variants, objectGraphChurn dataset)
cargo bench

# THE evidence tool — run directly, any variant/dataset
cargo run --release --bin gc_tail_harness -- \
  --variant growingLiveSet --dataset retainedCache
```

## Cross-language parity notes

- `collectorMatrix` has no meaningful Rust analog — there is no
  collector to put under a matrix of flags — and is implemented as a
  documented pass-through, behaviorally identical to
  `steadyHighAllocation`, included only so the variant matrix stays
  structurally parallel to the Java side.
- This lab's "calling Rust allocation-free by default" trap is the
  mistake of assuming Rust's absence of a GC means allocation is free;
  `gc_tail_harness`'s own measured allocation latency (tens of
  nanoseconds, never zero) and `growingLiveSet`'s real ≈28.4 ms
  reclamation event are direct evidence against that — the absence of an
  *automatic collector* is the finding, not the absence of any cost at
  all.
- No cross-harness ranking is published between `GcTailHarness` and
  `gc_tail_harness`; the native-Linux evidence runner treats Java as the
  measured side for this lab's publication numbers (benchmark.md), and
  Rust's runs are a separately disclosed instrument built around a
  structurally different mechanism.
