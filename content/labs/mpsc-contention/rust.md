# MPSC queues and producer contention — Rust

## Shared fixtures

```rust
pub mod fixtures {
    pub const PRODUCER_COUNTS: [usize; 2] = [2, 4];
    pub const ITEMS_PER_PRODUCER: usize = 2_000;
    pub const CAPACITY: usize = 1_024;
    pub const BATCH_SIZE: usize = 32;

    pub fn encode(producer_id: usize, local_seq: usize) -> i64 {
        ((producer_id as i64) << 32) | (local_seq as i64 & 0xFFFF_FFFF)
    }
}
```

Identical, byte-for-byte, to
`code/fixtures/mpsc-contention-fixtures.json` and to the Java kernels'
`MpscFixtures` (java.md). The shared `is_correct()` oracle (one function,
used by every variant's test) asserts exact total count, zero
duplicates, and strict per-producer FIFO order.

## Single shared MPSC (Vyukov's bounded ring)

```rust
struct Cell {
    sequence: AtomicI64,
    data: UnsafeCell<i64>,
}
unsafe impl Sync for Cell {}

pub(crate) struct Ring {
    mask: usize,
    cells: Box<[Cell]>,
    pub(crate) enqueue_pos: AtomicI64,
}
```

Same mechanism as the Java kernel: producers CAS-loop on the shared
`enqueue_pos`; the winning producer writes `data` through `UnsafeCell`
(exclusive access is guaranteed by having won the CAS) and then
release-stores the cell's `sequence`, which both signals readiness to the
consumer and (later) reuse-availability back to producers. `unsafe` here
is confined to the single-cell dereference in `produce_claimed`/
`consume_ready`, each documented with the precondition that makes it
sound (exclusive CAS ownership for the write; the sequence-established
happens-before edge for the read).

## Batched claims

`batched_claim::run` reuses `shared_mpsc::Ring` directly, replacing the
per-item CAS with one `enqueue_pos.fetch_add(batch_size)` per batch —
identical trade-off to the Java kernel: less claim-point contention, same
per-cell backpressure wait.

## Per-producer SPSC fan-in

`per_producer_fan_in` gives each producer its own private `SpscRing`
(`head`/`tail` atomics, `UnsafeCell` payload slots) — no shared claim
point at all. The consumer round-robins across an `Vec<Arc<SpscRing>>`.

## Mutex queue baseline

`mutex_queue::run` wraps a `std::collections::VecDeque` in a
`std::sync::Mutex` — no atomics, no lock-free reasoning, the same
"obviously correct" baseline as the Java kernel.

## Unbounded library queue comparison

`library_queue::run` uses `std::sync::mpsc::channel()` — Rust's own
standard-library MPSC channel, used exactly as shipped, unbounded by
construction. This is the Rust-side "reference point, not a lab-authored
mechanism" variant, paired with Java's `ConcurrentLinkedQueue` (see
benchmark.md's Method section on why bounded and unbounded variants are
never compared as if they had the same semantics).

## Correctness tests

5 tests in `src/lib.rs`'s `tests` module mirror `MpscOperationsTest`
exactly: for every variant and both correctness-suite producer counts (2
and 4), exact total count, zero duplicates, and strict per-producer FIFO
order. Run with:

```sh
cd content/labs/mpsc-contention/code/rust && cargo test
```

## `unsafe` inventory

All `unsafe` in this crate is confined to: (1) `unsafe impl Sync` on the
two shared cell/ring types (`shared_mpsc::Cell`, `shared_mpsc::Ring`,
`per_producer_fan_in::SpscRing`), asserting that the claim/publication
protocol — not the type system — prevents concurrent access to each
`UnsafeCell`; and (2) the one-line raw dereferences of those
`UnsafeCell`s, each in a function whose doc comment states the
precondition that makes it sound. No `unsafe` is used for any atomic
field — those are always accessed through the standard
`std::sync::atomic` API. Miri is not run in this repository's toolchain
today (no other lab here has a working Miri harness to build on); per
this lab's own guardrail against introducing new, unvetted toolchain
dependencies, that evidence source is marked capability-unavailable
rather than silently added just for this lab.

## Criterion benchmark

Each variant gets its own benchmark group, parameterized over the
correctness-suite producer counts (`BenchmarkId::new("producers", p)`),
matching the Java JMH class's `@Param` shape. Every closure spawns and
joins its own thread(s) internally, matching the Java side's operation
definition (see benchmark.md).

```sh
cd content/labs/mpsc-contention/code/rust && cargo bench
```

The runnable Cargo/Criterion project (with correctness tests in
`src/lib.rs`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/mpsc-contention/code/rust" rel="noopener"><code>content/labs/mpsc-contention/code/rust/</code></a>
in this site's repository.
