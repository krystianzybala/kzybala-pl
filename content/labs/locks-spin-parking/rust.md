# Locks, spin waiting and parking — Rust

## Shared fixtures

```rust
pub mod fixtures {
    pub const WORKER_COUNT: usize = 4;
    pub const OPS_PER_WORKER: usize = 2_000;
    pub const LONG_CS_BUSY_ITERATIONS: u32 = 200;
    pub const SPIN_LIMIT: u32 = 100;

    pub fn busy_work(iterations: u32) -> u64 { /* deterministic, side-effect-free */ }
}
```

Identical, byte-for-byte, to
`code/fixtures/locks-spin-parking-fixtures.json` and to the Java kernels'
`LocksFixtures` (java.md). The shared `LockResult::is_correct()` oracle
checks the shared counter's exact final value for every variant.

## Mutex variants (uncontended, short contended, long critical section)

```rust
pub mod mutex_kernel {
    fn run(worker_count: usize, ops_per_worker: usize, busy_iterations: u32) -> LockResult {
        let counter = Arc::new(Mutex::new(0u64));
        // each worker: lock(); [busy_work if busy_iterations>0]; *counter += 1; (unlock on drop)
    }
    pub fn uncontended() -> LockResult { run(1, OPS_PER_WORKER, 0) }
    pub fn short_contended(workers: usize, ops: usize) -> LockResult { run(workers, ops, 0) }
    pub fn long_critical_section(workers: usize, ops: usize, busy: u32) -> LockResult { run(workers, ops, busy) }
}
```

Same three-in-one structure as the Java kernel — `std::sync::Mutex`'s
guard is released automatically on drop, so there is no explicit
`unlock()` call, but the critical section's boundaries are identical.

## CAS loop

`cas_loop::run` retries `AtomicU64::compare_exchange` until success,
counting every failed attempt — the same mechanism and same disclosed
failure count as the Java kernel.

## Spin-then-park hybrid

`spin_then_park::SpinParkLock` spins up to `spin_limit` times using
`std::hint::spin_loop()`, then registers on a `Mutex<VecDeque<Thread>>`
waiter list and calls `std::thread::park()` — with the same
double-check-then-park missed-wakeup guard as the Java kernel.
`unlock()` pops one waiter and calls `Thread::unpark()`. Rust's std has
no lock-free queue, so the waiter list is guarded by a small `Mutex` of
its own (bookkeeping only — not the lock this variant is teaching); an
occasional spurious `unpark` (targeting a thread that already
re-acquired via the double-check path) is tolerated for the same reason
the Java kernel tolerates it — `park`/`unpark`'s permit is designed to
absorb exactly this without blocking incorrectly.

## Correctness tests

6 tests in `src/lib.rs`'s `tests` module mirror `LocksOperationsTest`
exactly: every variant reaches the exact expected counter value; a
dedicated `spin_limit=0` test exercises the park path under guaranteed
contention and reports (does not assert) the resulting park count, for
the same timing-race reason as the Java suite. Run with:

```sh
cd content/labs/locks-spin-parking/code/rust && cargo test
```

## `unsafe` inventory

This crate contains **no `unsafe` code** — every variant is built from
`std::sync::{Mutex, Arc}` and `std::sync::atomic` primitives, which is
itself part of the lesson: unlike the lock-free ring buffers in this
site's other concurrency labs, none of this lab's mechanisms require
`UnsafeCell`-based manual synchronization to implement correctly.

## Criterion benchmark

Each variant gets its own benchmark function, matching the Java JMH
class's `@Benchmark` methods one-for-one. Every closure spawns and joins
its own worker threads internally, matching the Java side's operation
definition (see benchmark.md).

```sh
cd content/labs/locks-spin-parking/code/rust && cargo bench
```

The runnable Cargo/Criterion project (with correctness tests in
`src/lib.rs`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/locks-spin-parking/code/rust" rel="noopener"><code>content/labs/locks-spin-parking/code/rust/</code></a>
in this site's repository.
