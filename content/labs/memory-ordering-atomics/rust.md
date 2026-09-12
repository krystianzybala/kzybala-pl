# Memory Ordering: VarHandles and Rust Atomics — Rust

## Shared fixtures

```rust
pub mod fixtures {
    pub const MAILBOX_PAYLOAD_A: i32 = 123_456_789;
    pub const MAILBOX_PAYLOAD_B: i32 = 987_654_321;
    pub const MAILBOX_TRIALS: u32 = 2_000;

    pub const SEQFLAG_UPDATE_COUNT: i32 = 2_000;
    pub const SEQFLAG_TRIALS: u32 = 50;
    pub fn seqflag_payload(seq: i32) -> i32 { seq * 7 + 3 }

    pub const COUNTER_THREAD_COUNT: u32 = 4;
    pub const COUNTER_INCREMENTS_PER_THREAD: u32 = 50_000;
    pub const COUNTER_EXPECTED_TOTAL: u64 =
        COUNTER_THREAD_COUNT as u64 * COUNTER_INCREMENTS_PER_THREAD as u64;

    pub const MAX_SPIN_ITERATIONS: u64 = 200_000_000;
}
```

Identical, byte-for-byte, to
`code/fixtures/memory-ordering-atomics-fixtures.json` and to the Java
kernels' `MemOrdFixtures` (java.md).

## A real semantic-equivalence gap: "plain" in Java vs. Rust

Before the variants: this lab's semantic-equivalence contract has one
intentional, documented difference between the languages, not a bug.
Java's plain (non-`volatile`) field access is *unspecified* under a data
race — you may observe a stale or partially-synchronized value, but the
JLS still defines *some* behavior. Rust's memory model has no such
allowance: a genuinely unsynchronized read/write race on non-atomic
memory from two threads is **undefined behavior**, full stop, regardless
of whether it "happens to work" on a given CPU. This lab's
`plain_broken_publication()` variants therefore use `Ordering::Relaxed`
atomics rather than a true data race — the closest legal Rust analogue to
"as weak as possible," matching this lab's own guardrail against
optimizing away safety guarantees to make a comparison look cleaner. The
two variants are not measuring identically-defined operations, and this
page does not claim they are; see the Terminology table in theory.md for
where "plain" and "Relaxed"/"Opaque" actually sit relative to each other.

## Single-slot mailbox

```rust
struct Slot {
    a: UnsafeCell<i32>,
    b: UnsafeCell<i32>,
    ready: AtomicI32, // 0 = unpublished, 1 = published, 2 = claimed (CAS variant only)
}
unsafe impl Sync for Slot {}

pub fn acquire_release_publication() -> Outcome {
    let slot = Arc::new(Slot { a: UnsafeCell::new(0), b: UnsafeCell::new(0), ready: AtomicI32::new(0) });
    let w = Arc::clone(&slot);
    let writer = thread::spawn(move || unsafe {
        *w.a.get() = MAILBOX_PAYLOAD_A;
        *w.b.get() = MAILBOX_PAYLOAD_B;
        w.ready.store(1, Ordering::Release);
    });
    let r = Arc::clone(&slot);
    let reader = thread::spawn(move || {
        let got = spin_until(|| r.ready.load(Ordering::Acquire) != 0);
        if got { unsafe { check_payload(&r) } } else { Outcome::TimedOut }
    });
    writer.join().unwrap();
    reader.join().unwrap()
}
```

`a`/`b` live in `UnsafeCell`, accessed only through `unsafe` blocks —
this mirrors the Java kernel's plain `int` fields exactly (no built-in
cross-thread guarantee of their own; all visibility comes from the
`ready` handshake). The `unsafe` surface is deliberately small and
isolated to two one-line dereferences per variant, each guarded by a
`# Safety` comment stating the precondition (the ready-flag protocol must
have already established happens-before). `cas_loop()` uses
`compare_exchange` twice (publish 0→1, claim 1→2) with `AcqRel` success
ordering, mirroring the Java kernel's double-CAS hand-off.
`fence_based()` layers an explicit `fence(Ordering::SeqCst)` on top of a
`Release`/`Acquire` backbone rather than attempting a from-scratch
`Relaxed`-plus-fence implementation — see "A note on the fence-based
variant" below for why.

The full source is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/memory-ordering-atomics/code/rust/src/lib.rs" rel="noopener"><code>code/rust/src/lib.rs</code></a>
(module `mailbox`) in this site's repository.

## Sequence flag plus payload

```rust
fn run(
    store: impl Fn(&Cell, i32) + Send + 'static,
    load: impl Fn(&Cell) -> i32 + Send + Sync + 'static,
    read_barrier: impl Fn() + Send + Sync + 'static,
) -> Outcome {
    // writer: for i in 1..=SEQFLAG_UPDATE_COUNT: store(2i-1); payload write; store(2i);
    // reader: s1 = load(); if even: payload read; read_barrier(); s2 = load();
    //         if s1 == s2: trust payload, else retry
}
```

Same shared `run()` shape as the Java kernel, parameterized by closures.
`read_barrier` is the fence between the payload read and the second seq
re-check — required regardless of the seq accesses' own ordering (see
"A note on the fence-based variant" below).

## A note on the fence-based variant

While building the Java equivalent of this exact seqlock (java.md links
the full javadoc), two standalone-fence-only implementations failed
intermittently on real ARM hardware across repeated whole-suite test
runs, even though each looked correct against the textbook
release/acquire fence-composition rules — the double-check pattern here
genuinely needs a StoreLoad edge that a release/acquire pair does not
supply (see theory.md's ordering/fence composition table). Rather than
repeat that expensive empirical debugging cycle in Rust on the same
hardware, this crate's `fence_based()` variants (mailbox and
sequence-flag) go straight to the version verified reliable in Java: a
proven `Release`/`Acquire` ordering on the atomic itself, plus one
additional explicit `fence(Ordering::SeqCst)` on publish. This is a real,
distinct technique (the "belt-and-suspenders" pattern some seqlock/RCU
implementations use) — not a from-scratch "only standalone fences"
implementation, which this lab's own construction showed is easy to get
subtly wrong even when it looks correct on paper.

## Counter update

```rust
pub fn volatile_seq_cst_publication() -> CounterResult {
    let total = run_threads(|c| {
        for _ in 0..COUNTER_INCREMENTS_PER_THREAD {
            let cur = c.value.load(Ordering::SeqCst);
            c.value.store(cur + 1, Ordering::SeqCst); // two separate ops, not one atomic RMW
        }
    });
    CounterResult { total, failed_cas: 0 }
}
```

`acquire_release_publication()` uses `value.fetch_add(1, Ordering::Release)`
— a genuine atomic RMW, and matches `COUNTER_EXPECTED_TOTAL` exactly every
run. `cas_loop()` retries `compare_exchange` until success, counting
failures. `fence_based()` uses a hand-rolled spinlock (`lock.compare_exchange`)
guarding a `Relaxed` increment — correct via mutual exclusion, the same
distinct mechanism as the Java kernel's test-and-CAS spinlock.
`plain_broken_publication()` and `volatile_seq_cst_publication()` are
deliberately **not** asserted to match the expected total in the
correctness suite; their totals are printed, not gated.

## Correctness tests

15 tests in `src/lib.rs`'s `tests` module mirror `MemOrdOperationsTest`
exactly: the four "correct" variants per dataset must produce the exact
expected outcome across the fixture trial count every time; the
deliberately-weak variants must complete without hanging, with their
observed rates printed rather than asserted. Run with:

```sh
cd content/labs/memory-ordering-atomics/code/rust && cargo test
```

## `unsafe` inventory

All `unsafe` in this crate is confined to: (1) `unsafe impl Sync` on the
two shared structs (`mailbox::Slot`, `sequence_flag::Cell`), asserting
that the ready-flag/seq protocol — not the type system — prevents
concurrent access to the `UnsafeCell` payload fields; and (2) the
one-line raw dereferences of those `UnsafeCell`s inside each variant,
each within a function whose name or comment states the precondition
(the flag/seq protocol must already have established happens-before
before the dereference executes). No `unsafe` is used for the atomic
fields themselves — those are always accessed through the standard
`std::sync::atomic` API. Miri is not run in this repository's CI/toolchain
today (checked before writing this page — no other lab here has a working
Miri harness to build on); per this lab's own guardrail against
introducing new, unvetted toolchain dependencies, that evidence source is
marked capability-unavailable rather than silently added just for this
lab.

## Criterion benchmark

Each dataset gets its own Criterion benchmark group, one function per
variant, matching the Java JMH class method-for-method (mailbox/sequence-
flag/counter-update × acquire-release/volatile-seq-cst/cas-loop/fence-
based — the deliberately-broken variants are excluded from the shared
benchmark, per this lab's own guardrail against benchmarking an
intentionally racy variant without isolation). Every closure spawns and
joins its own thread(s) internally per the datasets' inherently
multi-thread protocols — see benchmark.md for why this makes Criterion's
per-sample numbers a macro measurement of the full protocol including
thread spawn/join, not a steady-state microbenchmark, and why that means
these numbers are not directly comparable to the Java JMH numbers either.

```sh
cd content/labs/memory-ordering-atomics/code/rust && cargo bench
```

The runnable Cargo/Criterion project (with correctness tests in
`src/lib.rs`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/memory-ordering-atomics/code/rust" rel="noopener"><code>content/labs/memory-ordering-atomics/code/rust/</code></a>
in this site's repository.
