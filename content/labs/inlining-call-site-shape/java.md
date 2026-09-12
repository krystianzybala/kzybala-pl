# Inlining and call-site shape — Java track

Package `pl.kzybala.lab.inlining`: a shared pool of six concrete
strategies (`LongStrategy`), the manual enum alternative (`StrategyKind`),
the five dispatch variants (`InliningOperations`), and dev/publication
JMH benchmarks.

## The pieces

- **`LongStrategy`** — a `sealed interface` with six `record`
  implementations (`AddOne`, `MulThree`, `XorMask`, `ShiftAddSeven`,
  `SubEleven`, `ShiftOrOne`): six genuinely distinct concrete types, which
  is what makes the megamorphic variant actually megamorphic rather than
  six calls to the same class.
- **`StrategyKind`** — the same six transforms as a `switch`-dispatched
  enum: no interface, no vtable, nothing for C2's inline-cache profiling
  to track at all.
- **`InliningOperations.sumOversizedCallee`** — logically identical to
  `sumMonomorphic` (`x + 1`), but its private `bigAddOne` method is
  padded with 160 self-cancelling add/subtract statements specifically to
  push its bytecode size past HotSpot's inlining thresholds
  (`MaxInlineSize=35`, `FreqInlineSize=325` bytecodes by default) — a
  deliberate, documented technique, not an accidentally bloated method.
- **Three datasets, one strategy pool** — `pricingFunctions`,
  `codecStrategies` and `validationRules` differ only in their input
  generator (`InliningFixtures`); the strategy pool itself is shared
  because the variable this lab measures is call-site shape, not
  per-domain business logic (see rust.md for the same design choice on
  the Rust side).
- **`InliningBenchmark`** (dev/wiring) — the five variants over the
  pricing-functions dataset, unpinned.
- **`InliningLinuxEvidenceBenchmark`** (publication) — the full 5×3
  variant×dataset matrix, one pinned worker, the pairwise correctness
  oracle re-checked in `@Setup` before any timing.

## Build, correctness gate, run

```bash
cd content/labs/inlining-call-site-shape/code/java

# correctness gate — monomorphic==oversizedCallee, megamorphic==switchDispatch
mvn test

# build
mvn -q -DskipTests package

# dev smoke — all five variants, pricing-functions dataset
java -jar target/benchmarks.jar 'InliningBenchmark' -f 1 -wi 3 -w 1s -i 5 -r 1s -foe true

# one cell of the publication matrix, unpinned dev run
java -jar target/benchmarks.jar 'InliningLinuxEvidenceBenchmark' \
  -p variant=megamorphic -p dataset=codecStrategies -f 1 -wi 2 -w 1s -i 3 -r 1s -foe true

# -XX diagnostics (capability-detected; PrintInlining requires
# -XX:+UnlockDiagnosticVMOptions — see benchmark.md for what this host
# can and cannot produce)
java -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining \
  -jar target/benchmarks.jar 'InliningBenchmark.sum' -p variant=monomorphic -f 1 -wi 3 -w 1s -i 1 -r 1s 2>&1 | grep -i inlin
```

Publication-grade throughput numbers and the real inlining decisions
(from `-prof perfasm` or JITWatch) come only from the native-Linux
evidence runner (benchmark.md); the commands above validate wiring and
correctness on a development machine.

## Reading the results

- Compare `monomorphic` against `bimorphic` against `megamorphic` on the
  **same dataset** first — the expected direction is a real cost
  increase at each step, though the exact magnitude (and even whether a
  step is visible at all) is empirically host- and JDK-build-dependent
  (benchmark.md).
- Compare `megamorphic` against `switchDispatch` second — both apply the
  identical assignment, so any gap is attributable to dispatch mechanism
  alone, not to which strategies ran how often.
- Compare `oversizedCallee` against `monomorphic` third — both compute
  the identical `x + 1`; a gap here isolates the code-size inlining gate
  from the call-site-shape gate the other comparisons test.
