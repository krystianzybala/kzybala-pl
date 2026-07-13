# Cache coherence and MESI — Rust

Same pair of examples as the Java page: a field written by multiple threads
on different cores, and a field only ever touched by one thread. Neither
one is a benchmark.

## Shared-writer example

```rust
use std::sync::atomic::{AtomicU64, Ordering};

/// Two threads both call `increment` on the same instance, from different
/// cores — `Relaxed` is sufficient here since only the counter's own
/// atomicity matters, not ordering relative to other memory operations.
pub struct SharedWriter {
    counter: AtomicU64,
}

impl SharedWriter {
    pub fn new() -> Self { Self { counter: AtomicU64::new(0) } }
    pub fn increment(&self) { self.counter.fetch_add(1, Ordering::Relaxed); }
    pub fn value(&self) -> u64 { self.counter.load(Ordering::Relaxed) }
}
```

Two threads calling `increment` concurrently on a shared
`Arc<SharedWriter>` reproduce the "competing writers" coherence traffic the
interactive model shows — each `fetch_add` from one core invalidates the
other's cached copy of the line.

## Single-owner example

```rust
/// No atomics, no synchronization: exactly one thread ever touches `total`
/// for the value's whole lifetime, so there is no other core to invalidate
/// this line — the software analogue of an Exclusive/Modified single owner.
pub struct SingleOwner {
    total: u64,
}

impl SingleOwner {
    pub fn new() -> Self { Self { total: 0 } }
    pub fn add_from(&mut self, iterations: u64) {
        for i in 0..iterations { self.total += i; }
    }
    pub fn total(&self) -> u64 { self.total }
}
```

As with the Java example, neither struct here lets you directly observe or
force a MESI state from source code — `perf c2c` (Linux) is the tool for
observing the resulting cross-core traffic; see "Diagnostic methodology" in
`theory.md`.

The runnable project is in `code/rust/` alongside this file — see
`README.md` there for build and run instructions.
