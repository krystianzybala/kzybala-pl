# Escape analysis and scalar replacement — exercises

## Exercise 1 (diagnosis): the "harmless" toString

A hot method builds a small coordinate object, uses it locally to compute
a distance, and discards it. A colleague adds
`assert coordinate.toString() != null : "sanity check";` right before the
object goes out of scope, arguing "assertions are disabled in production,
so this changes nothing." Allocation rate at that call site jumps from
near-zero to a real, measurable rate. Diagnose why, using this lab's
mechanism, without assuming the assertion machinery itself is expensive.

**Success criteria:** you identify that even with `-ea` disabled,
depending on how the assertion is compiled/whether the JIT can prove the
condition path is dead, the *reference itself* may still need to be
provably unused down every path the compiler considers — and that
`toString()` specifically is exactly the kind of call `record`-generated
implementations route through machinery (string formatting, possibly
non-trivial method bodies) that may not inline cleanly, functioning as an
opaque-call-style escape even when the assertion never fires; you name
that the fix is not "assertions are free," it's checking with real
evidence (`-prof gc` / `-XX:+PrintEliminateAllocations`) whether this
specific call, in this specific hot path, on this specific JDK build,
actually stayed eliminated.

<details>
<summary>Hint</summary>

"Disabled at runtime" (the assertion body never executing) and "invisible
to the compiler's escape analysis" (the call site existing in the
compiled graph at all) are two different questions. This lab's
`passedToOpaqueCall` variant demonstrates the second one directly —
`toString()` is a real, uninlined-in-many-cases call in exactly the same
shape.
</details>

## Exercise 2 (implementation): find your own materialization boundary

Using this lab's Java code, add a **new** helper method that wraps
`EscapeAnalysisOperations.sumNonEscaping` in one extra layer of method
calls (a "pass-through" method that just forwards to it, with no
`DONT_INLINE` annotation) and measure whether B/op stays near zero
through the extra layer (dev machine, wiring-only — do not publish these
numbers). Predict, before measuring, whether one extra *inlinable* call
layer changes anything.

**Success criteria:** you state a specific prediction (e.g., "an
ordinary, small, hot method is very likely to be inlined by C2 regardless
of the extra layer, so B/op should stay near zero" — or the opposite,
with reasoning); you measure and report the actual B/op through the added
layer; and you connect the result back to this lab's actual mechanism
claim: escape analysis operates on what got inlined together, so the
number of *source-level* method calls is not, by itself, predictive —
only whether those calls got inlined is. Delete the added method
afterward; it is not part of the lab's fixture contract.

<details>
<summary>Hint</summary>

`-XX:+PrintInlining` will tell you directly whether your new pass-through
method was inlined — check that before drawing any conclusion from the
B/op number alone.
</details>

<details>
<summary>Solution</summary>

An ordinary small method with no artificial barrier is exactly the kind
of call C2's inliner handles easily — B/op should stay near zero through
an arbitrary number of *inlinable* layers, because after inlining the
whole call chain collapses into one compilation unit and escape analysis
sees straight through it. The lesson is that source-level call depth is
not the variable that matters; whether the JIT chose (and was able) to
inline is. If your measurement shows a real B/op jump through a plain,
small, hot pass-through method, that is itself worth investigating
further — an unusually deep inlining budget exhaustion, or the JIT
declining to inline for a reason worth finding via `-XX:+PrintInlining`,
not assuming.
</details>

## Exercise 3 (evidence interpretation): read the GC profiler output, not just the label

Below is the shape of two variants' `-prof gc` output from this lab's
publication runner (illustrative structure, not real captured evidence):

```text
variant=nonEscaping
  gc.alloc.rate.norm: 0.312 B/op
  gc.count: ≈ 0

variant=identityObserved
  gc.alloc.rate.norm: 32000004.891 B/op
  gc.count: 41.000
  gc.time: 28.000 ms
```

Answer from these two lines alone: (a) does `0.312 B/op` for
`nonEscaping` mean the object was allocated a fraction of a byte, or
something else entirely; (b) is `gc.time: 28.000 ms` the right number to
cite if asked "how much did GC cost this benchmark," or does it need
context from elsewhere in the same report; (c) name the one thing this
report tells you that assembly inspection (`-prof perfasm`) could confirm
even more directly, and why you might still want both.

**Success criteria:** (a) it means near-zero allocation with measurement
noise (JIT bookkeeping, JMH harness overhead) rounding to a small
non-zero figure — not a literal fractional-byte object, which cannot
exist; the honest reading is "effectively scalar-replaced," not "almost
allocated"; (b) `gc.time` alone needs the run's total wall-clock time for
context — 28 ms of GC pause during a 10-iteration, several-second
measurement window is a very different finding than 28 ms during a
100 ms window, and the report's total elapsed time (elsewhere in the same
JSON) is what turns the raw number into a percentage worth citing; (c)
`-prof perfasm` can show you the ACTUAL allocator call sequence in the
compiled code, directly confirming an allocation happens (rather than
inferring it from a nonzero counter) — worth having both because the
counter tells you the aggregate cost cheaply and continuously, while the
assembly confirms the mechanism once, precisely.

<details>
<summary>Hint</summary>

A B/op close to zero is never literally zero in practice — ask what else,
besides the object under test, might contribute a few stray bytes to a
JMH-harnessed measurement before concluding the number is "wrong."
</details>
