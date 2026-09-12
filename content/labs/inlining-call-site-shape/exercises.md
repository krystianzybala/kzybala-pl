# Inlining and call-site shape — exercises

## Exercise 1 (diagnosis): the "harmless" refactor

A codebase has a single `PricingStrategy` interface with two
implementations, called from one hot call site that has run monomorphic-
then-bimorphic for years without incident. A refactor introduces a
generic `LoggingStrategy` decorator that wraps *any* `PricingStrategy` for
observability, applied selectively to some call paths but not others —
crucially, the SAME underlying call site sometimes now receives a
`LoggingStrategy`-wrapped instance and sometimes an unwrapped one, in
addition to the original two concrete types. Throughput at that call site
drops sharply. Diagnose why, using this lab's mechanism, without assuming
the decorator itself does expensive work.

**Success criteria:** you identify that the decorator pattern, even
though it adds only cheap logging, introduces at least one MORE distinct
concrete type flowing through the same call site (the decorator class
itself, on top of however many types it wraps) — pushing a previously
bimorphic site toward or past megamorphic; you state that the decorator's
own logging cost is very likely NOT the primary cause of the slowdown,
the loss of inlining eligibility is; and you propose a diagnosis step —
checking the call site's actual observed type count (`-XX:+PrintInlining`
or equivalent), not guessing from the decorator's apparent cost.

<details>
<summary>Hint</summary>

Count concrete TYPES the call site sees, not variants of behavior. A
decorator is itself a new type at that call site, regardless of how
"thin" its logic is — this lab's `LongStrategy` pool demonstrates the
same principle with six arithmetic one-liners.
</details>

## Exercise 2 (implementation): measure your own inline-cache threshold

Using this lab's Java code, add a THIRD strategy to the `bimorphic`
variant (making it, at your machine's specific JIT build, either still
inlined or freshly megamorphic — you don't know until you measure) and
compare its ns/call against the existing `bimorphic` and `megamorphic`
variants (dev machine, wiring-only — do not publish these numbers).
Predict, before measuring, which existing variant your three-strategy
version will resemble more closely.

**Success criteria:** you state a specific prediction with reasoning
(e.g., "HotSpot's polymorphic inline cache commonly tracks up to a small,
fixed number of receivers before falling back — three may still fit, or
may not, depending on this specific JDK build"); you measure and report
which existing variant it resembles; and — regardless of which way it
went — you state explicitly that the *exact* threshold is a JVM
implementation detail, not something this lab's theory page promises a
specific number for. Delete the added strategy afterward; it is not part
of the lab's fixture contract.

<details>
<summary>Hint</summary>

This is exactly why benchmark.md says the mono→bimorphic→megamorphic
ratios are host- and JIT-version-dependent — your own measurement is a
small, personal instance of that same disclosed uncertainty, not a
special case.
</details>

<details>
<summary>Solution</summary>

There is no single correct answer here, by design — the polymorphic
inline cache's exact capacity is JVM-build-specific and not part of any
public API contract. What matters is the discipline: measure your own
build rather than assuming a textbook number, and report the *direction*
of the result (closer to bimorphic's cost or megamorphic's) rather than a
specific formula. If your three-strategy variant's cost jumps sharply
toward megamorphic's, you've empirically located this build's inline-
cache boundary; if it stays close to bimorphic's, this build tolerates
more than two receivers before falling back.
</details>

## Exercise 3 (evidence interpretation): read the inlining log, not the label

Below is the shape of two variants' `-XX:+PrintInlining` output from this
lab's publication runner (illustrative structure, not real captured
evidence):

```text
variant=monomorphic
  @ 12 pl.kzybala.lab.inlining.AddOne::apply (6 bytes)   inline (hot)

variant=oversizedCallee
  @ 12 pl.kzybala.lab.inlining.InliningOperations::bigAddOne (742 bytes)   too large
```

Answer from these two lines alone: (a) does the `(742 bytes)` figure for
`bigAddOne` explain the "too large" decision on its own, or would you
need another number to be sure; (b) `monomorphic`'s `AddOne::apply` is
tiny (6 bytes) — does that alone guarantee it gets inlined, or could a
call site still be rejected for a reason unrelated to size; (c) name the
one thing this log format tells you that a bare ns/call number never
could.

**Success criteria:** (a) you need the compiler's actual size threshold
for the context (which differs for cold vs. hot/frequently-executed
methods — `MaxInlineSize` vs. `FreqInlineSize`) to be certain 742 bytes
crossed it rather than assuming from the label alone; the log already
states the decision, but confirming *why* the threshold applied requires
that second number; (b) no — size is necessary but not sufficient; a
tiny, hot method at a call site the JIT judges megamorphic is still
rejected for shape, not size, so a small callee is not a standing
guarantee; (c) the inlining log states the compiler's actual DECISION and
its stated REASON directly, whereas a bare timing number only lets you
infer a decision indirectly and can be confounded by unrelated effects
(warm-up, GC, measurement noise) that never show up in the log at all.

<details>
<summary>Hint</summary>

A timing number answers "how long." An inlining log answers "what did
the compiler actually decide, and why." Which one do you need to confirm
this lab's actual mechanism claim, versus just its consequence?
</details>
