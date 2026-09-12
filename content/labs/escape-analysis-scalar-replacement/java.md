# Escape analysis and scalar replacement — Java track

Package `pl.kzybala.lab.escapeanalysis`: a small two-field `Aggregate`
record, the five usage-pattern variants (`EscapeAnalysisOperations`), and
dev/publication JMH benchmarks built specifically to be run with `-prof
gc`.

## The pieces

- **`Aggregate`** — a two-field `record` (`x`, `y`), deliberately trivial
  so that when a variant fails to scalar-replace it, the reason is the
  *usage pattern*, never the shape of the object.
- **`AggregateHolder`** — the `storedIntoField` variant's target: a
  mutable field an `Aggregate` gets written into every iteration,
  overwriting the previous value — unconditional GlobalEscape.
- **`EscapeAnalysisOperations.opaqueSum`/`opaqueBuild`** — `@CompilerControl(DONT_INLINE)`
  methods, the same deliberate optimization barrier this lab's
  plab-202 prerequisite uses to defeat range-check elimination, reused
  here to defeat escape analysis instead: a real, non-inlined call
  boundary C2 cannot see across.
- **`sumIdentityObserved`** calls `System.identityHashCode(a)` for its
  side effect only — forcing the object's identity to be observable — and
  never adds the (non-deterministic) hash value to the accumulated sum,
  keeping the correctness oracle fully deterministic.
- **Three datasets, one aggregate shape** — `coordinate`, `resultWrapper`
  and `parserState` differ only in their input generators
  (`EscapeAnalysisFixtures`); the aggregate itself is shared because the
  variable this lab measures is usage pattern, not per-dataset business
  logic (same design choice as plab-202).

## A real finding from building this lab

Running `EscapeAnalysisBenchmark` with `-prof gc` on this repository's
development machine showed exactly the mechanism this lab teaches:
`nonEscaping` measured **~2 B/op** (noise-level — scalar-replaced) while
`returnedObject`, `storedIntoField` and `passedToOpaqueCall` each measured
**exactly 32,000,0xx B/op** for 1,000,000 elements — 32 bytes per
`Aggregate` instance (a compressed-oops object header plus two `long`
fields), materialized every single call, with dozens of real GC cycles
recorded (`gc.count`). `identityObserved` allocated the identical 32
bytes per element but ran roughly **15–20× slower** than the other
materialized variants in that same run — allocation alone does not
explain that gap; the identity-hash-code computation itself carries a
real, separate cost on top of materialization. This is a genuine,
reproducible finding worth checking on your own machine (dev-only, never
published as evidence) before assuming it generalizes to the native-Linux
host.

## Build, correctness gate, run

```bash
cd content/labs/escape-analysis-scalar-replacement/code/java

# correctness gate — all five variants agree on every dataset's total
mvn test

# build
mvn -q -DskipTests package

# dev smoke with allocation profiling — the real point of this lab
java -jar target/benchmarks.jar 'EscapeAnalysisBenchmark' -f 1 -wi 3 -w 1s -i 5 -r 1s -prof gc -foe true

# one cell of the publication matrix, unpinned dev run
java -jar target/benchmarks.jar 'EscapeAnalysisLinuxEvidenceBenchmark' \
  -p variant=storedIntoField -p dataset=resultWrapper -f 1 -wi 2 -w 1s -i 3 -r 1s -foe true

# escape-analysis diagnostics (capability-detected; requires a debug/
# diagnostic-unlocked JVM build — see benchmark.md for what this host
# can and cannot produce)
java -XX:+UnlockDiagnosticVMOptions -XX:+PrintEscapeAnalysis -XX:+PrintEliminateAllocations \
  -jar target/benchmarks.jar 'EscapeAnalysisBenchmark.sum' -p variant=nonEscaping -f 1 -wi 3 -w 1s -i 1 -r 1s 2>&1 | grep -i escap
```

Publication-grade allocation and latency numbers come only from the
native-Linux evidence runner (benchmark.md); `-prof gc` itself is real,
portable evidence usable on any JVM — only the *magnitude* of the
timing numbers is restricted to the reference host.

## Reading the results

- Always read `gc.alloc.rate.norm` (B/op) before ns/op — it directly
  answers "was this scalar-replaced," which the timing number only
  implies.
- Compare `nonEscaping` against the other four on B/op first — the
  expected result is a clean drop to near-zero for `nonEscaping` alone;
  if any of the other four ALSO show near-zero B/op, the JIT inlined more
  aggressively than this lab's barriers intended (check
  `-XX:+PrintInlining` before trusting the comparison).
- Compare `identityObserved` against `storedIntoField`/`returnedObject`/
  `passedToOpaqueCall` on ns/op, not B/op — all four should show similar
  allocation; a large ns/op gap on top of similar B/op isolates the
  identity-hash-code operation's own cost from the allocation cost.
