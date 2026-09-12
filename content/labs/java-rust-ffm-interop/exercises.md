# Java-Rust interop with FFM downcalls and upcalls — exercises

Attempt each exercise before opening the collapsed hints/solutions, and
verify against the stated success criteria.

## Exercise 1 — Diagnosis (the "FFM is slow" benchmark that measured the wrong thing)

A colleague reports "FFM downcalls are 50x slower than pure Java" after
benchmarking a function that processes a 10,000-element array by calling
a scalar Rust downcall inside a loop, one element at a time, and
comparing it to a pure-Java loop doing the same math. They conclude "FFM
has too much overhead to be usable."

**Task:** explain what their benchmark actually measured, and why "FFM
downcalls are slow" is the wrong conclusion to draw from it.

**Success criteria:** you name the specific design choice in their
benchmark (not "FFM" itself) responsible for the 50x gap, and you state
what number their same benchmark would likely show if they called one
batched or zero-copy downcall instead.

<details>
<summary>Hint</summary>

Look at this lab's own `scalarDowncallPerItem` vs.
`zeroCopyBufferDowncall` numbers in benchmark.md. Both use FFM. Why is
one ~140x more expensive than the other in the same run?

</details>

<details>
<summary>Solution</summary>

Their benchmark measured **call granularity**, not "FFM's overhead" as a
concept — this is exactly the "calling per field and blaming FFM" trap
theory.md names. A scalar downcall per array element pays the fixed
per-crossing cost 10,000 times; a batched or zero-copy downcall pays it
once for the whole array. This lab's own numbers (benchmark.md) show
roughly a 140x gap between `scalarDowncallPerItem` and
`zeroCopyBufferDowncall` in one development run — using the *identical*
FFM linkage machinery in both cases. The correct conclusion is "our call
shape was chatty," not "FFM is slow"; re-shaping the same benchmark to
call one batched or zero-copy downcall instead would very likely bring
its number down close to (or below) the pure-Java baseline, using the
exact same linkage mechanism they concluded was unusable.

</details>

## Exercise 2 — Implementation (a batched upcall)

The lab's `rustToJavaUpcall` variant calls back into Java once per array
element — the chatty extreme for the upcall direction, mirroring
`scalarDowncallPerItem`'s chatty downcall. **Task:** implement a batched
upcall variant: Rust computes the ENTIRE transformed array first, then
calls back into Java exactly ONCE, passing a pointer and length covering
the whole result (Java reads the whole buffer out of that one callback
invocation, e.g. by copying it into a `double[]` inside the callback
method).

**Success criteria (measure, don't assert):**

1. Correctness: the batched-upcall variant delivers the exact same
   transformed values, in the same order, as the existing per-element
   upcall variant — verify with a test comparing both against the pure
   Java baseline.
2. Measure the batched upcall's cost against both `rustToJavaUpcall`
   (chatty) and `zeroCopyBufferDowncall` (chatty-free downcall) — where
   does it land, and is it closer to one or the other?
3. State one new lifetime hazard this design introduces that the
   per-element upcall didn't have — connect it to the "passing invalid
   lifetimes" trap and the pointer Rust passes to the single callback
   call.

<details>
<summary>Hint</summary>

The per-element upcall passes one `f64` by value on the stack each time
— trivially safe. What does the batched upcall have to pass instead, and
whose memory does that pointer point into?

</details>

## Exercise 3 — Evidence interpretation (JFR method-profiling samples)

Below is a **synthetic teaching example** in JFR's `jfr print --events
jdk.ExecutionSample` style output — constructed for this exercise, not
captured from any run. It is educational material for practicing
profiler-sample interpretation only: it is never used as measurement
evidence, never supports this lab's performance conclusions, and never
enters a comparison or maturity calculation. (The lab's real profiler
evidence comes exclusively from the native-Linux evidence runner and is
imported with full provenance; see `benchmark.md`.) Two 5-second profiles
of two different variants processing the same data continuously, top
frames only, labels removed:

```
Profile A — top stack frames by sample count
  62% pl.kzybala.lab.ffminterop.RustLibrary::transformScalar (downcall stub)
  19% java.lang.invoke.MethodHandle::invokeExact
  11% pl.kzybala.lab.ffminterop.FfmInteropOps::scalarDowncall (loop overhead)
   8% (other)

Profile B — top stack frames by sample count
   4% pl.kzybala.lab.ffminterop.RustLibrary::transformBatch (downcall stub)
   3% java.lang.invoke.MethodHandle::invokeExact
  91% pl.kzybala.lab.ffminterop.FfmInteropOps::batchedDowncall (MemorySegment.copy calls)
   2% (other)
```

**Task:** decide which profile corresponds to `scalarDowncall` and which
to `batchedDowncall`, and justify from where the samples land — not from
which profile "looks busier." Then state one conclusion this data
**cannot** support.

**Success criteria:** your identification is correct, your reasoning
names what the dominant frame in each profile actually represents
mechanically, and your "cannot conclude" statement is genuinely
unsupported by this data rather than merely cautious.

<details>
<summary>Hint</summary>

In Profile B, the downcall stub itself accounts for only 4% of samples,
while 91% of time is attributed to the *calling* method, not the call
target. What is `batchedDowncall`'s own method body doing besides
issuing the one downcall?

</details>

<details>
<summary>Solution</summary>

**Profile A is `scalarDowncall`; Profile B is `batchedDowncall`.**

- In Profile A, 62% of samples land directly in the downcall stub itself
  (`transformScalar`), consistent with a loop that spends most of its
  time actually crossing the FFI boundary, over and over — exactly what a
  chatty, one-crossing-per-element variant should look like.
- In Profile B, only 4% of samples land in the downcall stub — this
  variant makes far fewer crossings — while 91% land in
  `batchedDowncall`'s own method body, which (per java.md) is where the
  `MemorySegment.copy` calls copying the array in and out live. The
  profile is correctly attributing most of the cost to the *copying*
  code, not the *call*, which is exactly the mechanism theory.md's
  "silently copying buffers" trap warns about misattributing.
- This also illustrates why "which one looks like it's doing more" is the
  wrong read: Profile A's flatter, dominant-in-the-call-target shape
  doesn't mean it does *more total work* than Profile B's
  dominant-in-the-caller shape — it means the two variants spend their
  time in structurally different places for structurally different
  reasons.

**What this data cannot support:** any claim about the *absolute*
duration of either variant's operation, or a ranking between them by
speed — sampling profiles show WHERE time was spent as a proportion,
not HOW MUCH wall-clock time either variant took in total; that
information lives in the throughput/latency numbers in `benchmark.md`,
not in a percentage-of-samples table. It also cannot be used to attribute
cost to "MethodHandle overhead" specifically without further evidence —
19% and 3% here could include JIT-related sampling artifacts specific to
this one synthetic example, not a general MethodHandle cost claim.

</details>
