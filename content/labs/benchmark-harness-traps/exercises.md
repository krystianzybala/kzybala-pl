# Benchmark harness traps — exercises

## Exercise 1 (diagnosis): the impossible parser

A pull request claims a parser optimization made parsing **40× faster**
and attaches this JMH output from the author's laptop:

```text
Benchmark                    Mode  Cnt   Score   Error  Units
ParserBench.parseOptimized   avgt    2   61.42     NaN  ns/op
ParserBench.parseBaseline    avgt    2 2444.87     NaN  ns/op
```

The "optimized" benchmark method differs from the baseline in exactly two
ways: the input string was moved from a `@State` field to a
`private static final String`, and the parsed checksum is no longer
returned. Diagnose the speedup.

**Success criteria:** you identify *both* harness defects, name the
mechanism behind each, and state which corrected variant pair in this
lab's benchmark class reproduces each defect; you also name the two
red flags visible in the output itself (sample count and missing error
estimate).

<details>
<summary>Hint</summary>

61 ns for parsing 256 values is under a quarter of a nanosecond per value.
Compare that against the cost of a single load. What may the JIT do with a
computation whose input is a compile-time constant and whose output nobody
reads? And what does `Cnt 2` with `Error NaN` tell you about the run
itself?
</details>

<details>
<summary>Solution</summary>

The `static final` input makes the computation constant-provable
(constant folding), and dropping the return value makes it unobservable
(dead-code elimination) — either alone can hollow out the benchmark;
together the method measures almost nothing. This is exactly the
`foldedInput` vs `runtimeInput` pair plus the missing-result-sink
discussion in `HarnessTrapsBenchmark`. The output red flags: two
iterations cannot support an error estimate (`NaN`) and a single short
run on a laptop has no fork-to-fork spread — the number carries no
uncertainty information at all.
</details>

## Exercise 2 (implementation): make the trap visible

Add a new benchmark method `discardedResult()` to your local copy of
`HarnessTrapsBenchmark` that calls `dispatch(runtimeSeed, runtimeRounds)`
and ignores the returned value (return type `void`, no Blackhole). Run it
against `returnedResult` on the reduction dataset with the smoke command
from java.md.

**Success criteria:** the discarded variant reports a cost far below the
corrected variant and far below any plausible cost for 4096 memory reads;
you can explain the gap in one sentence using the term dead-code
elimination; the correctness suite still passes (the kernel itself is
untouched); and you delete the method afterwards — a benchmark that
measures nothing must not stay in the class.

<details>
<summary>Hint</summary>

If the gap does not appear, check that the method really has no observable
result: returning the value, consuming it, or storing it to a field all
count as observations. `-prof perfasm` (on Linux) or `-prof gc` can
corroborate: the eliminated variant allocates nothing and executes almost
no kernel instructions.
</details>

<details>
<summary>Solution</summary>

A `void` method whose computation has no side effects is fully removable
under the as-if rule, so JMH times an empty invocation loop. The reported
ns/op collapses to invocation overhead. The one-sentence explanation: the
compiler deleted the unobserved computation, so the benchmark measured
the harness, not the kernel.
</details>

## Exercise 3 (evidence interpretation): read the raw output

Below is genuine, unedited Criterion terminal output shape (values elided
to X.XX — read the structure, not the magnitudes):

```text
scalar/folded_input     time:   [X.XX ns X.XX ns X.XX ns]
scalar/runtime_input    time:   [XX.XX ns XX.XX ns XX.XX ns]
                        change: [-1.2% +0.1% +1.4%] (p = 0.87 > 0.05)
                        No change in performance detected.
```

Answer from the output alone: (a) what do the three bracketed values on a
`time:` line mean; (b) what hypothesis does the `p = 0.87` refer to, and
what does it justify concluding; (c) which of the two groups, if either,
can be compared against a JMH `avgt` score of the same kernel, and under
what conditions?

**Success criteria:** (a) lower CI bound / point estimate / upper CI bound
of the mean sample time; (b) the null hypothesis that this run's
distribution equals the saved baseline's — it justifies only "no detected
change between these two Criterion runs", never a cross-harness or
cross-machine claim; (c) neither can be compared as a ranking — a JMH
comparison requires the same kernel semantics (shared fixtures), disclosed
settings for both instruments, same host and imported provenance, and even
then it is presented as two instruments' views, not one winner.

<details>
<summary>Hint</summary>

Criterion's `change:` line always compares against its own previous saved
run of the *same* benchmark id. Ask yourself what population each
statistic describes before asking what it "proves".
</details>
