# Thread-per-core and shared-nothing sharding — Rust

## Shared fixtures and workload plan

```rust
pub mod fixtures {
    pub const KEY_COUNT: usize = 16;
    pub const SHARD_COUNT: usize = 4;
    pub const REQUESTS_PER_KEY_UNIFORM: usize = 500;
    pub const REQUESTER_THREADS: usize = 4;
    pub const SKEWED_HOT_KEY_REQUESTS: usize = 4_000;
    pub const SKEWED_COLD_KEY_REQUESTS: usize = 200;

    pub fn shard_for(key: usize) -> usize { key % SHARD_COUNT }
}
```

`WorkloadPlan` mirrors the Java kernel's plan builder exactly — identical,
byte-for-byte, to `code/fixtures/thread-per-core-sharding-fixtures.json`
and to the Java kernels' `TpcFixtures`/`WorkloadPlan` (java.md). The
shared `TpcResult::is_correct_uniform()`/`is_correct_skewed()` oracle
checks every key's exact expected final count.

## Shared concurrent map

```rust
pub fn run(plan: &WorkloadPlan) -> TpcResult {
    let counters: Vec<AtomicU64> = (0..KEY_COUNT).map(|_| AtomicU64::new(0)).collect();
    // every requester thread: counters[key].fetch_add(1, AcqRel), any key
}
```

## Fixed shards with mutex

`mutex_shards::run` partitions keys into `SHARD_COUNT` shards via the
same `shard_for(key)` function, each key's counter behind its own
`Mutex<u64>`.

## Single-writer thread-per-core

```rust
pub fn run(plan: &WorkloadPlan) -> TpcResult {
    // SHARD_COUNT owner threads, each with its own std::sync::mpsc::Receiver
    // requester threads: senders[shard_for(key)].send(key)
    // owner threads: rx.recv() in a loop, local counters[key] += 1, no lock needed
}
```

Routing uses `std::sync::mpsc` — Rust's own standard-library MPSC
channel, one per shard — matching this lab's prerequisite [MPSC Queues
and Producer Contention](/lab/mpsc-contention/) lab's "library queue"
reference point rather than reimplementing a queue from scratch. **This
lab's own benchmark.md shows this variant costing more than the mutex-
shards variant for this cheap-per-item workload in both languages** —
disclosed, not hidden; see benchmark.md's Method section.

## Skewed key distribution

`WorkloadPlan::skewed()` builds the same mechanisms' inputs with key 0
receiving `SKEWED_HOT_KEY_REQUESTS` and every other key receiving
`SKEWED_COLD_KEY_REQUESTS` — no new kernel code needed.

## Rebalance simulation

`rebalance_simulation::run` runs the single-writer mechanism in two
half-sized phases with different key-to-shard mappings, joining every
phase-1 owner thread before phase 2 begins — identical stop-the-world
model to the Java kernel (see theory.md's Assumptions and scope). The
pause is measured via `std::time::Instant` as `rebalance_cost_nanos`.

## Correctness tests

7 tests in `src/lib.rs`'s `tests` module mirror `TpcOperationsTest`
exactly. Run with:

```sh
cd content/labs/thread-per-core-sharding/code/rust && cargo test
```

## `unsafe` inventory

This crate contains **no `unsafe` code** — every variant is built from
`std::sync::{Mutex, Arc}`, `std::sync::atomic` and `std::sync::mpsc`
safe primitives.

## Criterion benchmark

Each mechanism/dataset pair gets its own benchmark function, matching
the Java JMH class's `@Benchmark` methods one-for-one.

```sh
cd content/labs/thread-per-core-sharding/code/rust && cargo bench
```

The runnable Cargo/Criterion project (with correctness tests in
`src/lib.rs`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/thread-per-core-sharding/code/rust" rel="noopener"><code>content/labs/thread-per-core-sharding/code/rust/</code></a>
in this site's repository.
