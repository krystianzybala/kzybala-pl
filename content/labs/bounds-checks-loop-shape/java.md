# Bounds checks and loop shape — Java track

Package `pl.kzybala.lab.boundschecks`: deterministic backing-array
generation shared with the correctness fixture
(`BoundsChecksFixtures`), the five variants over three datasets
(`BoundsChecksOperations`), and dev/publication JMH benchmarks.

## The pieces

- **`BoundsChecksFixtures`** — `buildBacking(length)` fills
  `backing[i] = i`; `randomPermutation` is the byte-for-byte same
  Fisher-Yates construction already pinned in
  content/labs/cache-locality-working-set.
- **`BoundsChecksOperations.opaqueLength`** — annotated
  `@CompilerControl(CompilerControl.Mode.DONT_INLINE)`: the deliberate
  optimization barrier that stops C2 from proving the returned bound
  equals `backing.length`, so the `opaqueLimit` variant's per-iteration
  check cannot be eliminated the way `canonical`'s can.
- **The `unchecked` variant is `MemorySegment`-backed, not
  `sun.misc.Unsafe`-backed** — `MemorySegment.ofArray(backing)` wraps the
  *same* on-heap array (no copy, no off-heap allocation) and is read via
  `segment.get(ValueLayout.JAVA_INT, offset)`. This directly tests the
  "assuming `MemorySegment` is automatically slower" trap: nothing here
  claims it removes bounds checking (the FFM API checks segment bounds
  internally), only that routing access through it is not an automatic
  performance penalty.
- **`sliceSafeIterator`** receives a `long[]`... (an `int[]`) already
  copied via `Arrays.copyOfRange` in `@Setup` — Java has no zero-copy
  borrowed-slice view for a primitive array, so the copy happens once,
  outside the timed region, and the timed method is a plain enhanced-for
  over the copy (see the equivalence-contract note below).
- **`BoundsChecksBenchmark`** (dev/wiring) — the five variants over the
  primitive-arrays dataset, unpinned.
- **`BoundsChecksLinuxEvidenceBenchmark`** (publication) — the full 5×3
  variant×dataset matrix, one pinned worker, the closed-form correctness
  oracle re-checked in `@Setup` before any timing.

## Semantic-equivalence note: Java has no borrowed slice type

Rust's `slicesSubranges` variants build a genuine, zero-copy `&[i32]` via
`&backing[start..end]` (rust.md). Java has no equivalent view type for a
primitive array — `canonical`/`opaqueLimit`/`irregularIndex`/`unchecked`
therefore index the *original* backing array with explicit
`[sliceStart, sliceStart+N)` bounds (no copy either), and only
`safeIterator` needs a materialized sub-array, built once in setup, to
give Java's enhanced-for something to iterate. Every variant visits the
identical values and produces the identical sum; only Rust's slice
variants additionally get a compiler-checked *view type*, which Java's
language design does not offer for primitives — a real, documented
asymmetry, not a workaround.

## Build, correctness gate, run

```bash
cd content/labs/bounds-checks-loop-shape/code/java

# correctness gate — all five variants agree on every dataset's total
mvn test

# build
mvn -q -DskipTests package

# dev smoke — all five variants, primitive-arrays dataset
java -jar target/benchmarks.jar 'BoundsChecksBenchmark' -f 1 -wi 3 -w 1s -i 5 -r 1s -foe true

# one cell of the publication matrix, unpinned dev run
java -jar target/benchmarks.jar 'BoundsChecksLinuxEvidenceBenchmark' \
  -p variant=unchecked -p dataset=stridedAccess -f 1 -wi 2 -w 1s -i 3 -r 1s -foe true

# -XX diagnostics (capability-detected; requires a debug JVM build to see
# elimination decisions directly — see benchmark.md for what this host
# can and cannot produce)
java -XX:+UnlockDiagnosticVMOptions -XX:+PrintCompilation \
  -jar target/benchmarks.jar 'BoundsChecksBenchmark.sum' -p variant=canonical -f 1 -wi 3 -w 1s -i 1 -r 1s
```

Publication-grade throughput numbers and the real annotated assembly
come only from the native-Linux evidence runner (benchmark.md); the
commands above validate wiring and correctness on a development machine.

## Reading the results

- Compare `canonical` against `opaqueLimit` on the **same dataset**
  first — this isolates range-check elimination specifically, since both
  visit the identical elements in the identical order.
- Compare `unchecked` against `canonical` second — a genuinely
  successful RCE means the two should be close; a large, consistent gap
  is worth investigating as evidence the canonical loop's checks were
  *not* actually eliminated on this host/JDK build, not evidence that
  `unsafe`-style access is a reliable speedup by default.
- `irregularIndex` combines two effects (no check-elimination proof, plus
  the cache-locality cost of non-sequential access) — do not attribute
  its full cost to bounds checks alone without the counter evidence
  (`branches`, `cache-misses`) to separate them.
