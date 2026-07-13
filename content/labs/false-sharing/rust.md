# False sharing — Rust

Same experiment as the Java example: two atomics, each incremented in a
tight loop by its own dedicated thread, with the only variable being memory
layout.

## Adjacent atomics (the bug)

```rust
use std::sync::atomic::AtomicU64;

#[repr(C)]
pub struct SharedCounters {
    pub counter_a: AtomicU64,
    pub counter_b: AtomicU64,
}

impl SharedCounters {
    pub fn new() -> Self {
        Self { counter_a: AtomicU64::new(0), counter_b: AtomicU64::new(0) }
    }
}
```

`#[repr(C)]` fixes declaration order (Rust's default `repr(Rust)` layout is
otherwise unspecified and the compiler is free to reorder or interleave
fields). With `repr(C)`, two adjacent 8-byte `AtomicU64`s are 16 bytes apart
— comfortably inside one 64-byte line on the common case. Thread 1 spins on
`counter_a.fetch_add(1, Ordering::Relaxed)`, thread 2 on `counter_b`. Neither
thread touches the other's atomic, and `Relaxed` ordering is sufficient for
correctness here (this is not an ordering bug) — the slowdown is purely
coherence traffic from sharing a line.

## Aligned wrapper (the fix)

```rust
use std::sync::atomic::AtomicU64;

#[repr(align(64))]
pub struct CacheLineAligned<T>(pub T);

pub struct PaddedCounters {
    pub counter_a: CacheLineAligned<AtomicU64>,
    pub counter_b: CacheLineAligned<AtomicU64>,
}

impl PaddedCounters {
    pub fn new() -> Self {
        Self {
            counter_a: CacheLineAligned(AtomicU64::new(0)),
            counter_b: CacheLineAligned(AtomicU64::new(0)),
        }
    }
}
```

`#[repr(align(64))]` is an alignment guarantee the compiler enforces — unlike
the Java manual-padding fields, this cannot be optimized away, because
alignment is part of the type's layout contract, not a value the optimizer
can prove unobserved. Each `CacheLineAligned<AtomicU64>` starts a fresh
64-byte-aligned line, so `counter_a` and `counter_b` are guaranteed to be on
separate lines *if the target's actual cache line is 64 bytes or a divisor of
it* — document that assumption at the call site; it does not hold on every
microarchitecture (some ARM parts use 128-byte lines, some embedded targets
32).

## Alignment and ordering assumptions, stated explicitly

- `64` is an assumed common cache-line size for the target, not something
  Rust or the hardware guarantees at compile time. On an unknown target,
  either measure it or pad to the largest line size you must support.
- `Ordering::Relaxed` is enough for these counters because nothing else in
  the program depends on the *order* in which the two counters' updates
  become visible relative to other memory operations — only that each
  individual `fetch_add` is atomic. If your real counters guard or publish
  other state, you need `Acquire`/`Release`, not `Relaxed` — that is an
  orthogonal decision from the alignment fix in this lab.

## Criterion benchmark

```rust
use criterion::{criterion_group, criterion_main, Criterion};
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::thread;

fn run_concurrent_increments<F: Fn() + Send + Sync + 'static>(iters: u64, op_a: Arc<F>, op_b: Arc<F>) {
    let a = op_a.clone();
    let b = op_b.clone();
    let t1 = thread::spawn(move || { for _ in 0..iters { a(); } });
    let t2 = thread::spawn(move || { for _ in 0..iters { b(); } });
    t1.join().unwrap();
    t2.join().unwrap();
}

fn bench_shared(c: &mut Criterion) {
    c.bench_function("shared_counters", |b| {
        b.iter(|| {
            let counters = Arc::new(SharedCounters::new());
            let c1 = counters.clone();
            let c2 = counters.clone();
            run_concurrent_increments(
                100_000,
                Arc::new(move || { c1.counter_a.fetch_add(1, Ordering::Relaxed); }),
                Arc::new(move || { c2.counter_b.fetch_add(1, Ordering::Relaxed); }),
            );
        });
    });
}

fn bench_padded(c: &mut Criterion) {
    c.bench_function("padded_counters", |b| {
        b.iter(|| {
            let counters = Arc::new(PaddedCounters::new());
            let c1 = counters.clone();
            let c2 = counters.clone();
            run_concurrent_increments(
                100_000,
                Arc::new(move || { c1.counter_a.0.fetch_add(1, Ordering::Relaxed); }),
                Arc::new(move || { c2.counter_b.0.fetch_add(1, Ordering::Relaxed); }),
            );
        });
    });
}

criterion_group!(benches, bench_shared, bench_padded);
criterion_main!(benches);
```

The runnable project is in `code/rust/` alongside this file — see
`README.md` there for build and run instructions.
