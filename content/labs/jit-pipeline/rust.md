# JIT pipeline — Rust

The Rust side of this lab is deliberately small: an ahead-of-time (AOT)
baseline (`aot_baseline`) running the byte-identical pricing kernel (same
xorshift64 inputs, same exact integer math, same fixture totals) as a
natively-compiled binary.

## What the AOT baseline is — and is not

There is no interpreter, no tiered compilation, no profile-driven
speculation and no deoptimization in the Rust binary: its "warm-up" only
stabilizes CPU frequency and caches. That asymmetry is the entire lesson —
which is why this baseline is a **separate scenario by policy**: it is
never merged with or compared against JVM warm-up phases, and even against
JVM steady state it is presented as a compilation-model design trade-off,
never a language winner claim (`docs/measurement-environments.md`,
comparison-guard rules).

The executable benchmark and correctness suite are used internally to
produce the evidence shown by this laboratory.
