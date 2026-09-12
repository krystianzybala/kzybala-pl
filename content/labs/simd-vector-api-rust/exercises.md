# SIMD: Java Vector API and Rust — exercises

## Exercise 1 — Diagnosis: why did explicit SIMD lose to the scalar loop?

You are handed the following (simplified) Rust reduction kernel and told
its benchmark shows it running SLOWER than a plain scalar `for` loop over
the same data:

```rust
unsafe fn explicit_simd(values: &[i32]) -> i32 {
    let mut acc: i32x4 = zero();
    for chunk in values.chunks_exact(4) {
        let v = load(chunk);
        let sum = horizontal_add(v.add(acc)); // reduce every iteration
        acc = broadcast(sum);
    }
    horizontal_add(acc)
}
```

Using this lab's own mechanism (theory.md) and its own real Rust-track
finding (rust.md), name what is wrong with this loop, and describe the
minimal change that fixes it without changing the function's result.

**Success criteria:** you identify that `horizontal_add` runs once per
chunk instead of once total, and you describe accumulating in the vector
register across iterations with a single reduce at the end.

<details>
<summary>Hint</summary>

Count how many times `horizontal_add` (a lane-crossing reduce) actually
runs, versus how many times it strictly needs to run.
</details>

<details>
<summary>Solution</summary>

`horizontal_add` runs once per 4-element chunk — for a million-element
input, that is 250,000 horizontal reduces, each a real, non-free
cross-lane operation, when the loop only strictly needs ONE at the very
end. This is exactly the mistake this lab's own `sum_min_max::explicit_simd`
made in its first working version (rust.md): reducing to scalar every
iteration instead of accumulating in the vector register across
iterations. The fix: keep `acc` as a running vector accumulator
(`acc = acc.add(v)`, no reduce inside the loop) and call
`horizontal_add(acc)` exactly once, after the loop — with one caveat this
lab also had to handle (Exercise 2): a narrow accumulator can then
silently overflow if left unreduced for too long.
</details>

## Exercise 2 — Implementation: fix the accumulator without losing the speedup

Given the fixed loop from Exercise 1 (accumulate in a vector register,
reduce once at the end), the input can be up to several million `i32`
values. Modify the loop so it cannot silently overflow the way this
lab's `sum_min_max`/`SumMinMaxKernel` kernel originally did (java.md,
rust.md), while still avoiding a horizontal reduce on every single
iteration.

**Success criteria:** your fix periodically flushes the vector
accumulator into a wider scalar total at an interval that cannot
overflow the narrow lane, without reducing on every iteration.

<details>
<summary>Hint</summary>

The fix does not have to choose between "reduce every iteration" (slow,
Exercise 1) and "reduce only once" (fast but overflow-prone). There is a
third option in between.
</details>

<details>
<summary>Solution</summary>

Flush the vector accumulator to a wide scalar total (`i64`/`long`)
periodically — not every iteration, and not only at the very end — then
reset the vector accumulator to zero and continue. This lab's own fix
used a fixed `FLUSH_INTERVAL` (1,000 vector iterations) chosen so that,
given this dataset's known value range, the vector accumulator cannot
approach `i32`'s overflow bound between flushes. A scalar `long`/`i64`
accumulator never has this problem because integer widening happens
automatically; a fixed-width vector lane does not widen, so the
periodic-flush pattern is the general fix whenever a narrow vector
accumulator must run over more iterations than its width can safely
absorb.
</details>

## Exercise 3 — Evidence interpretation: reading a real speedup table

This lab's own development-machine run (java.md) produced these real
`explicitSimd` speedups over `scalarBaseline`, for four kernels on the
same host, same JVM, same day:

| Kernel | Speedup |
|---|---|
| byteClassification | ≈51.5× |
| sumMinMax | ≈4.0× |
| thresholdFilter | ≈3.6× |
| dotProduct | ≈1.5× |

A colleague reads this table and concludes: "SIMD in Java just isn't
worth it for `dotProduct` — 1.5× barely matters, let's rip it out."
Using this lab's compute-bound-vs-bandwidth-bound mechanism (theory.md),
explain what is missing from that conclusion, and what you would check
before agreeing or disagreeing with it.

**Success criteria:** you explain why comparing raw multipliers across
kernels with different bottlenecks is misleading, and you name at least
one concrete check (e.g. bandwidth counters on the target host) before
accepting the colleague's conclusion.

<details>
<summary>Hint</summary>

The four kernels are not doing the same AMOUNT of work per element, and
they are not all limited by the same resource (ALU throughput vs. memory
bandwidth). A smaller multiplier is not automatically a smaller absolute
win, and it is not automatically evidence that SIMD "isn't worth it."
</details>

<details>
<summary>Solution</summary>

The colleague is comparing raw multipliers across kernels that are
bottlenecked by different things, which this lab's theory.md explicitly
warns against. `byteClassification`'s huge ≈51.5× reflects both a very
cheap per-element op (two comparisons) AND the widest lane count in this
lab's matrix (16 bytes per instruction) — it is compute-cheap and
lane-dense, the best case for SIMD. `dotProduct`'s modest ≈1.5× is
explicitly attributed in java.md to it being closer to
memory-bandwidth-bound: the CPU is spending real time waiting on data to
stream in, so a wider ALU has less idle time to fill. A 1.5× speedup that
is bandwidth-bound-limited is not "SIMD not working" — it may be close to
the ceiling physically available for that kernel's access pattern, and
ripping it out would still lose a real, measured 1.5×. Before agreeing or
disagreeing, you would want to check: is `dotProduct` actually
bandwidth-bound on the TARGET host (not just this dev machine) — e.g. via
`perf stat`'s bandwidth counters, per benchmark.md's required metrics —
rather than assume the dev-machine mechanism transfers unchanged; and
whether 1.5× on a hot enough path is still worth keeping regardless of
how it compares to the OTHER kernels' multipliers, since kernels are
never meant to be compared against each other in this lab's own
methodology (benchmark.md: "never comparing across kernels or
languages").
</details>
