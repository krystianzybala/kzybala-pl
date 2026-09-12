# Safepoints and time to safepoint — Rust track

Crate `safepoints_lab`: the fixed constants and deterministic sum shared
with the Java side, and `safepoint_coordinator` — a real, explicitly-built
stop-the-world coordinator, since Rust has no runtime equivalent to
observe.

## The pieces

- **`safepoints_lab::sum_range`** — the identical partition-invariant
  sum as the Java side; every dataset's partitioning must sum to the same
  fixture-pinned total.
- **`safepoint_coordinator`** — a shared `AtomicBool` "pause requested"
  flag and `AtomicUsize` acknowledgment counter; worker threads check the
  flag between chunks (or, for `longLoopSparsePolls`, only once per full
  pass) and increment the counter when they see it set. **"Time to
  pause" is the coordinator's own measured wall-clock time** from setting
  the flag to observing every required worker's acknowledgment — built
  by hand, because nothing in the Rust runtime does this for you.
- **Workers loop, they do not run once.** A single pass over this lab's
  range is fast enough on modern hardware that a worker could finish and
  exit before the coordinator ever requests a pause — which would make
  "wait for every acknowledgment" spin forever. Every worker instead
  repeats its chunked pass until an explicit `stop` flag is set,
  recording only its *first* completed pass's sum for the correctness
  check (every pass computes the identical value). This was a real bug
  caught during this lab's own development (see below).
- **Only registered threads are ever waited on.** `threadInNativeCall`'s
  extra thread and `manyIdleThreads`' 300 extra threads are spawned but
  never added to the coordinator's required-acknowledgment count — they
  cost the "time to pause" measurement nothing, not because Rust
  discovered they were safe the way the JVM does, but because this code
  never asked them to participate in the first place.

## A real bug found while building this lab's Rust track

The first version of `safepoint_coordinator` had workers run their
chunked pass exactly once. On this repository's development machine,
summing 400,000,000 `u64`s is fast enough that a single-worker run
**deadlocked**: the worker finished and exited before the coordinator's
fixed startup delay elapsed, so the "wait for acknowledgment" loop spun
forever waiting for a thread that no longer existed. The fix — workers
loop indefinitely until told to stop, always available to acknowledge —
is now how every variant works, and it is itself a small illustration of
this lab's Rust-track point: nothing prevents a worker from finishing
"too fast" for a pause to ever be observed unless the code is written to
guarantee it stays available. The JVM's own safepoint protocol has no
such race by construction, because it does not depend on a worker being
mid-loop at all.

## What was actually measured (dev-only, never published)

Running `safepoint_coordinator` across all 15 (variant × dataset) cells
on this repository's development machine, every run terminated
correctly with the identical checksum. `longLoopSparsePolls` combined
with `nativeSleepDowncall` produced a real, dramatic time-to-pause
(≈408 ms) — with sparse polling, a worker only checks the pause flag once
per full pass, and a pass on this dataset includes eight 50 ms native
sleeps, so "time to pause" ends up close to a full pass's wall-clock
length. `threadFleet` runs showed real variance from ordinary system
contention (16 threads on a machine with fewer physical cores) rather
than from the polling mechanism itself — worth naming honestly rather
than attributing to a language difference.

## Build, correctness gate, run

```bash
cd content/labs/safepoints-ttsp/code/rust

# correctness gate — every partitioning sums to the identical total
cargo test

# lints and formatting
cargo clippy --all-targets
cargo fmt --check

# THE evidence tool — run directly, any dataset/variant
cargo run --release --bin safepoint_coordinator -- \
  --variant manyIdleThreads --dataset threadFleet
```

## Cross-language parity notes

- `allocationPressureTrigger` has no meaningful Rust analog — there is
  no GC to put under pressure — and is implemented as a documented
  pass-through, behaviorally identical to `cooperativeLoop`, included
  only so the variant matrix stays structurally parallel to the Java
  side. Its numbers are never compared to Java's `allocationPressureTrigger`
  results as if they measured the same thing.
- This lab's "claiming Rust has no pauses" trap is the mistake of reading
  a small `timeToPauseNanos` as proof Rust is immune to coordination
  costs; `longLoopSparsePolls`/`nativeSleepDowncall`'s ≈408 ms result is
  direct evidence Rust can produce a large, real pause once you build
  coordination that has to wait on something — the absence of an
  *automatic* mechanism is the finding, not the absence of the
  phenomenon.
- No cross-harness ranking is published between `SafepointHarness` and
  `safepoint_coordinator`; the native-Linux evidence runner treats Java
  as the measured side for this lab's publication numbers (benchmark.md),
  and Rust's runs are a separately disclosed instrument built around a
  structurally different mechanism.
