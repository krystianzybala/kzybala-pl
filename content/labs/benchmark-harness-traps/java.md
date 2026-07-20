# Benchmark harness traps — Java track

The Java side uses **JMH**, the OpenJDK microbenchmark harness, because a
hand-rolled `System.nanoTime()` loop falls into every trap this lab
teaches at once. One benchmark class,
`pl.kzybala.lab.harnesstraps.HarnessTrapsBenchmark`, holds six measurement
shapes of the same four kernels (`TrapKernels`), so the harness is the
only variable.

## What each JMH feature is for

- **`@State(Scope.Thread)`** — benchmark inputs live in a state object
  with a defined lifecycle. The trap alternative is `static` mutable
  fields, which both leak state between iterations and give the JIT
  constant-provable values.
- **Forks (`-f`)** — each trial runs in a fresh JVM. JIT decisions,
  profile pollution and heap layout are per-process; forks turn "one
  lucky JVM" into a distribution. The lab's single-fork variant exists to
  show what the missing spread looks like.
- **Warm-up (`-wi`, `-w`)** — discarded iterations while tiered
  compilation settles. The publication profile discards several seconds
  per fork; a zero-warm-up run is a smoke-only wiring check.
- **`Blackhole`** — an escape-proof result sink. `consumedResult(bh)`
  demonstrates it; `returnedResult()` shows the equivalent implicit sink
  (JMH consumes return values). A method that computes and discards is
  not in the class, because after DCE it measures an empty loop.
- **`@CompilerControl(DONT_INLINE)`** — pins the call shape of the shared
  `dispatch` method so inlining differences between variants cannot
  masquerade as harness effects.
- **`@Param`** — `dataset={scalar,reduction,parser,counter}` selects the
  kernel per process invocation; parameters are recorded in the JMH JSON
  so every sample identifies its dataset.
- **Profilers (`-prof`)** — `-prof gc` reports `gc.alloc.rate.norm`
  (allocation per operation, one of this lab's required metrics);
  `-prof perfasm` (Linux, requires perf and hsdis) shows the JIT assembly
  that proves or disproves folding claims.

## Build, correctness gate, run

```bash
cd content/labs/benchmark-harness-traps/code/java

# correctness gate — the fixture oracle must pass before any timing
mvn test

# build the benchmark jar
mvn -q -DskipTests package

# development smoke (wiring check only — zero statistical value)
java -jar target/benchmarks.jar 'HarnessTrapsBenchmark' \
  -p dataset=scalar -f 1 -wi 1 -w 1s -i 2 -r 1s -foe true

# allocation per operation (required metric; any dataset)
java -jar target/benchmarks.jar 'HarnessTrapsBenchmark.runtimeInput' \
  -p dataset=parser -f 1 -wi 3 -w 1s -i 5 -r 1s -prof gc
```

Publication-grade numbers come only from the native-Linux evidence runner
(see benchmark.md) — the commands above validate wiring and correctness on
a development machine.

## Reading the trap variants

- `foldedInput` passes compile-time constants; on the scalar dataset C2
  is *allowed* to fold aggressively; if it does, the implausibly small
  ns/op is the demonstration — and if it does not, the pair measures how
  much provability the JIT actually exploits. Either outcome is evidence.
- `runtimeInput` loads the same values from state fields — the corrected
  baseline for every other comparison.
- `setupInsideTimed` rebuilds the dataset inside the measured operation;
  compare against `setupOutside` to see setup cost being attributed to
  the kernel.
- The counter dataset resets per invocation inside the operation — the
  reset is part of the defined operation, so no state leaks between
  invocations (the correctness test proves the leak exists without it).

Every variant computes the identical fixture result
(`TrapKernelsCorrectnessTest.everyBenchmarkVariantComputesTheFixtureResult`)
— a variant that got faster by computing something else would fail the
gate, not set a record.
