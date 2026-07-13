# Cache hierarchy — Rust

Same technique as the Java example: **pointer chasing** through a
single-cycle permutation table, so each access depends on the value read by
the previous one and the compiler cannot vectorize or reorder around the
memory-latency chain being measured.

## Building the chase tables

```rust
//! Pointer-chase tables for the cache-hierarchy benchmark. Each table is a
//! single N-element cycle: starting at index 0 and repeatedly following
//! `next[idx]` visits every element exactly once before returning to 0.

/// A sequential cycle: 0 -> 1 -> 2 -> ... -> size-1 -> 0. Maximal spatial locality.
pub fn sequential_cycle(size: usize) -> Vec<u64> {
    (0..size).map(|i| ((i + 1) % size) as u64).collect()
}

/// A random single-cycle permutation built with Sattolo's algorithm. Plain
/// Fisher-Yates can produce several short sub-cycles, which would let a
/// pointer chase loop through only a small hot subset of the array —
/// Sattolo's algorithm guarantees exactly one cycle covering all `size`
/// elements.
pub fn random_cycle(size: usize, seed: u64) -> Vec<u64> {
    let mut perm: Vec<usize> = (0..size).collect();
    let mut state = seed.max(1); // xorshift64 needs a non-zero seed
    let mut next_rand = |bound: usize| -> usize {
        // xorshift64: a small, dependency-free PRNG — good enough for
        // building a benchmark fixture, not for anything security-sensitive.
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        (state % bound as u64) as usize
    };
    for i in (1..size).rev() {
        // next_rand(i), not next_rand(i + 1) as in Fisher-Yates — excluding
        // self-swaps is what makes Sattolo's algorithm produce a single
        // cycle instead of a random permutation (usually several disjoint
        // cycles).
        let j = next_rand(i);
        perm.swap(i, j);
    }
    let mut next = vec![0u64; size];
    for i in 0..size {
        next[perm[i]] = perm[(i + 1) % size] as u64;
    }
    next
}
```

## The benchmark

```rust
use criterion::{black_box, criterion_group, criterion_main, Criterion};
use cache_hierarchy_lab::{random_cycle, sequential_cycle};

// 16 KB of u64s — comfortably fits inside L1D on any current desktop/laptop core.
const SMALL_SIZE: usize = 2_048;
// 128 MB of u64s — exceeds any consumer last-level cache.
const LARGE_SIZE: usize = 16_777_216;
const CHASES: usize = 1_000_000;

fn chase(next: &[u64], steps: usize) -> u64 {
    let mut idx: u64 = 0;
    for _ in 0..steps {
        idx = next[idx as usize];
    }
    idx
}

fn bench_sequential_small(c: &mut Criterion) {
    let table = sequential_cycle(SMALL_SIZE);
    c.bench_function("sequential_small", |b| b.iter(|| chase(black_box(&table), CHASES)));
}

fn bench_random_small(c: &mut Criterion) {
    let table = random_cycle(SMALL_SIZE, 1);
    c.bench_function("random_small", |b| b.iter(|| chase(black_box(&table), CHASES)));
}

fn bench_sequential_large(c: &mut Criterion) {
    let table = sequential_cycle(LARGE_SIZE);
    c.bench_function("sequential_large", |b| b.iter(|| chase(black_box(&table), CHASES)));
}

fn bench_random_large(c: &mut Criterion) {
    let table = random_cycle(LARGE_SIZE, 2);
    c.bench_function("random_large", |b| b.iter(|| chase(black_box(&table), CHASES)));
}

criterion_group!(benches, bench_sequential_small, bench_random_small, bench_sequential_large, bench_random_large);
criterion_main!(benches);
```

`black_box(&table)` prevents the optimizer from treating the table as a
compile-time constant. The closure's *return value* doesn't need a second,
explicit `black_box` call here — Criterion's own `Bencher::iter` already
wraps every closure call in `black_box` internally
(`for _ in 0..self.iters { black_box(routine()); }`, in `criterion`'s
`bencher.rs`), so the result of `chase(...)` is protected from dead-code
elimination automatically.

**A caveat about `black_box`'s guarantee.** Criterion's `black_box` has two
implementations: a nightly-only one backed by the compiler's own (unstable)
`test::black_box`, and the stable-Rust fallback this project actually uses
(plain `rustc`, no nightly toggle), which performs a `std::ptr::read_volatile`
followed by `std::mem::forget`. Criterion's own doc comment on that fallback
is explicit that it "may cause some performance overhead or fail to prevent
code from being eliminated" — it is a strong convention, not a hard
compiler guarantee. Compiling this crate's `chase` function in isolation
with `rustc -O --emit=asm` confirms LLVM keeps the load and the bounds
check in the loop for this specific code shape (see below), which is the
concrete evidence this lab relies on rather than trusting the black-box
comment alone.

**Bounds checks and the `for _ in 0..steps` loop.** `next[idx as usize]`
performs a normal Rust bounds check on every access, for the same reason
as the Java version: `idx` is data-dependent (read from the array itself),
not a value LLVM can statically prove stays in `0..next.len()`, so it is
not eligible for bounds-check elision. Disassembling the release-mode
`chase` function shows this directly — every iteration is:

```asm
LBB0_2:
    cmp   x0, x1        ; idx >= len ?
    b.hs  LBB0_6        ; branch to the panic path if so
    ldr   x0, [x8, x0, lsl #3]  ; idx = next[idx]  (lsl #3 = *8, the u64 element width)
    subs  x2, x2, #1    ; steps -= 1
    b.ne  LBB0_2         ; loop while steps != 0
```

The same disassembly also answers the "does `for _ in 0..steps` add
iterator overhead over a C-style counted loop?" question directly: it
doesn't — the `Range<usize>` iterator compiles down to exactly the
`subs`/`b.ne` decrement-and-branch pair shown above, indistinguishable
from a hand-written counted loop. Both languages retain one bounds check
per access, and it applies equally to every benchmark in this file, so it
does not bias the sequential/random comparison.

**Why a hand-rolled xorshift64 rather than the `rand` crate?** To keep this
a zero-extra-dependency example next to `criterion` — any seedable PRNG
works here, since it's only building a fixed benchmark fixture, not
anything where randomness quality matters.

**What isn't controlled:** this benchmark does not pin the process to a
specific CPU core, and macOS's thread-affinity API is documented as
advisory on Apple silicon rather than a hard pin — see `benchmark.md` for
what this looked like in practice during this lab's own reruns.

The runnable Cargo/Criterion project is at <a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/cache-hierarchy/code/rust" rel="noopener"><code>content/labs/cache-hierarchy/code/rust/</code></a> in this site's repository.
