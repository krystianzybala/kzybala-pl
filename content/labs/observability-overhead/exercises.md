# Logging, Metrics and Profiling Overhead — exercises

Attempt each exercise before opening the collapsed hints/solutions, and
verify against the stated success criteria.

## Exercise 1 — Diagnosis (the debug log that cost real money at WARN level)

A hot-path method contains:

```java
logger.debug("processing order " + orderId + " with items " + formatItems(items) + " at " + Instant.now());
```

The production log level is `WARN`. A profiler flags this line as a
measurable contributor to p99 latency anyway.

**Task:** explain exactly why this line costs real time even though it
never logs anything in production, and rewrite it so a disabled level
costs (approximately) nothing.

**Success criteria:** you correctly identify that the entire expression —
string concatenation, the `formatItems(items)` call, and
`Instant.now()` — is evaluated *before* `logger.debug` gets a chance to
check the level, because Java evaluates method arguments eagerly. Your
rewrite either guards the whole block with `if (logger.isDebugEnabled())`
or uses a lazy/supplier-based logging API, and you can state why the
guard must wrap `formatItems(items)` too, not just the string
concatenation.

<details>
<summary>Hint</summary>

Compare this exactly to this lab's `DISABLED_EAGER_LOGGING` vs.
`DISABLED_LAZY_LOGGING` variants — which one does this code resemble, and
what is the one structural change that moves it to the other?

</details>

<details>
<summary>Solution</summary>

Java evaluates every argument to a method call before the call happens,
with no exceptions — `logger.debug(expr)` always evaluates `expr` fully,
regardless of what `debug()` does internally with the result. The string
concatenation, `formatItems(items)` (which could itself be
allocation-heavy if `items` is large), and `Instant.now()` (a real system
call) all execute on every single invocation, whether or not the DEBUG
level is enabled — this is exactly this lab's `DISABLED_EAGER_LOGGING`
shape. The fix is a guard that runs *before* any of that:

```java
if (logger.isDebugEnabled()) {
    logger.debug("processing order " + orderId + " with items " + formatItems(items) + " at " + Instant.now());
}
```

The guard must wrap the entire expression, not just part of it — wrapping
only the string concatenation while still calling `formatItems(items)`
unconditionally outside the guard leaves the most expensive part
(potentially proportional to `items.size()`) unprotected, reproducing the
same trap in a subtler form.

</details>

## Exercise 2 — Implementation (bound the metrics label cardinality)

`METRICS_LABELS` in this lab has no upper bound on `distinctLabelsSeen` —
`highCardinalityKey`'s 500 keys is a fixed, known dataset, but a
production system with an unbounded key space (e.g. a raw user id) could
grow without limit.

**Task:** implement a bounded variant that caps the number of distinct
label keys tracked (e.g. at 100): once the cap is reached, route any
*new* key's hits into a single overflow bucket instead of creating a new
entry, while keys already tracked keep updating normally.

**Success criteria (measure, don't assert):**

1. `distinctLabelsSeen` never exceeds `cap + 1` (the cap plus the overflow
   bucket) regardless of how many actually-distinct keys appear in the
   input.
2. `labelHitsTotal` still equals the total event count exactly (no hit is
   silently lost — it goes to the overflow bucket instead of its own key).
3. You can explain one real cost of this bound: what information about
   the overflowed keys is now permanently lost, and why that might be an
   acceptable trade for bounded memory.

<details>
<summary>Hint</summary>

Track whether a key is "new" with a simple `map.containsKey(key)` check
before deciding whether to grow the map or route to the overflow bucket —
the decision only depends on the map's current size at that moment.

</details>

## Exercise 3 — Evidence interpretation (a flame graph time budget)

Below is a **synthetic teaching example** — constructed for this exercise,
not captured from any run — modeled on the shape a real profiler's
self-time breakdown takes for one request handler. It is educational
material for practicing evidence interpretation only: it is never used as
measurement evidence, never supports this lab's performance conclusions,
and never enters a comparison or maturity calculation.

```
Self-time breakdown for handleRequest() (100% = 4.2ms average):
  businessLogic()              62%   (2.60ms)
  logger.info(...) formatting  24%   (1.01ms)
  metricsRegistry.increment()   9%   (0.38ms)
  everything else               5%   (0.21ms)
```

**Task:** the team looks at this breakdown and proposes deleting the
`logger.info` call entirely to "get back 24% of latency." Name a better
first fix that this lab's variant matrix suggests, and state one
conclusion this breakdown **cannot** support.

**Success criteria:** you propose checking whether this is eager or
guarded logging (i.e., whether the message is only built when the level
is actually enabled) before deleting anything — if it's eager logging at
a disabled level, switching to guarded logging captures most or all of
that 24% without losing any log output at all when the level *is*
enabled, which deleting the line permanently forfeits. Your "cannot
conclude" statement recognizes that a flame graph's percentage breakdown
says nothing about whether the underlying log level is even enabled in
production, or what fraction of that 24% is formatting versus lock
contention versus I/O.

<details>
<summary>Solution</summary>

The better first fix is exactly this lab's disabled-eager-vs-lazy
distinction: check whether `logger.info(...)`'s argument is built eagerly
(string concatenation evaluated as a method argument) or lazily (guarded
by `isInfoEnabled()` or a supplier-based API) *before* concluding the
message-formatting cost is unavoidable. If it's eager and the level
happens to be disabled in production, switching to a guard recovers most
of that 24% while keeping full logging available when the level actually
is enabled — strictly better than deleting the statement, which loses
observability the team may need later, permanently, to save the same
latency a one-line guard fix would recover. **What this breakdown cannot
support:** whether the info level is even enabled right now in production
(a flame graph's percentages describe where time went *in this profiled
run*, not the runtime configuration that determined whether formatting
happened at all), or how that 24% decomposes between formatting,
synchronization, and actual I/O — a profiler's self-time bucket for one
line can hide several genuinely different costs this lab treats as
separate mechanisms (format, deliver, contend).

</details>
