# CAS contention and backoff — Rust

## CAS retry loop

```rust
use std::sync::atomic::{AtomicU64, Ordering};

pub struct CasCounter {
    value: AtomicU64,
}

impl CasCounter {
    // fetch_update already implements the retry loop internally.
    pub fn increment_via_builtin(&self) -> u64 {
        self.value.fetch_update(Ordering::SeqCst, Ordering::SeqCst, |v| Some(v + 1)).unwrap() + 1
    }

    pub fn increment_manually(&self) -> u64 {
        loop {
            let old = self.value.load(Ordering::SeqCst);
            let updated = old + 1;
            if self.value.compare_exchange(old, updated, Ordering::SeqCst, Ordering::SeqCst).is_ok() {
                return updated;
            }
            // else: someone else moved it first — retry
        }
    }
}
```

`compare_exchange` returns a `Result`; `Err` carries the current value so a
retry doesn't need a second load (elided above for symmetry with the Java
example, which re-reads explicitly). `fetch_update` is the built-in
equivalent of the manual loop. `SeqCst` is used throughout for the
clearest cross-thread guarantee — see the
[Memory Ordering in Java and Rust](/lab/memory-ordering/) lab for whether a
weaker ordering would suffice for a given real use case.

## Single-writer alternative

```rust
pub struct SingleWriterCounter {
    // No atomics needed — correct only because exactly one thread ever
    // calls increment().
    value: u64,
}

impl SingleWriterCounter {
    pub fn increment(&mut self) -> u64 {
        self.value += 1;
        self.value
    }
}
```

See `benchmark.md` for how dramatically this outperforms even *uncontended*
CAS in this run, and the theory section for why that gap is not a blanket
recommendation to avoid atomics.

## Criterion benchmark

```rust
use cas_contention_lab::{CasCounter, SingleWriterCounter};
use criterion::{black_box, criterion_group, criterion_main, Criterion};
use std::sync::Arc;
use std::thread;

const INCREMENTS_PER_THREAD: u64 = 20_000;

fn run_contended(thread_count: usize) {
    let counter = Arc::new(CasCounter::new());
    let handles: Vec<_> = (0..thread_count)
        .map(|_| {
            let c = Arc::clone(&counter);
            thread::spawn(move || {
                for _ in 0..INCREMENTS_PER_THREAD { c.increment_manually(); }
            })
        })
        .collect();
    for h in handles { h.join().unwrap(); }
}

fn bench_cas_8_threads(c: &mut Criterion) {
    c.bench_function("cas_increment_8_threads", |b| b.iter(|| run_contended(8)));
}
```

**Why `black_box` matters here, concretely:** the single-writer benchmark's
first draft measured 314.75 picoseconds for 160,000 increments — a
physically impossible ~2 femtoseconds per increment. LLVM had proven the
whole loop's result was never observed and eliminated it entirely. Wrapping
the read in `black_box(counter.increment())` forced the optimizer to treat
it as observable, producing the real (and still very fast — a plain scalar
increment with no synchronization) figure in `benchmark.md`. The `run_contended`
functions above don't need this: real thread spawning and joining, plus
genuine atomic operations with real side effects, cannot be proven dead by
the optimizer.

The runnable Cargo/Criterion project is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/cas-contention/code/rust" rel="noopener"><code>content/labs/cas-contention/code/rust/</code></a>
in this site's repository.
