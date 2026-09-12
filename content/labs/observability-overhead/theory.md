# Logging, Metrics and Profiling Overhead — theory

## Performance question and hypothesis

**Question:** how much latency and allocation does observability add to
the path it is supposed to explain?

**Hypothesis:** eager formatting, shared locks, labels and stack walking
can dominate hot paths; guarded, structured and sampled instrumentation
can retain evidence at bounded cost.

**What would disprove it:** if disabled eager logging cost the same as
disabled lazy (guarded) logging (implying formatting-before-the-guard-check
is free), or if sampled tracing at a 1-in-8 rate cost the same as tracing
every call (implying sampling doesn't actually reduce cost), the
cost-is-in-the-mechanism model taught here would be wrong.

## Learning objective

Show that "instrumentation" is not one cost but several distinct,
separately-priced mechanisms — string formatting, lock contention, map
bookkeeping, stack walking — and that a log statement's *disabled* level
does not automatically make it free: whether it is free depends entirely
on whether the expensive part happens before or after the level check.

## Prerequisites

- The [Benchmark Harness Traps](/lab/benchmark-harness-traps/) lab — the
  same measurement discipline (setup outside the timed region, avoiding
  dead-code elimination) applies here to instrumentation code specifically.
- The [Backpressure and Bounded Pipelines](/lab/backpressure-bounded-pipelines/)
  lab — this lab's async-bounded-logging variant is a direct application
  of that lab's bounded-reject policy to a log queue instead of a work
  queue.

### Pre-lab diagnostic

A team sets their production log level to `WARN`, well above their
scattered `logger.debug(...)` calls, and assumes those calls now cost
"basically nothing" since nothing is written. Under load, they still see
a measurable latency regression traced directly to those disabled debug
statements. What could make a log call that writes nothing still cost
real time?

(Answer at the end of this page.)

## Terminology

| Term | Meaning in this lab |
|---|---|
| Eager formatting | Building the final log message string (concatenation, `String.format`, `format!`) *before* checking whether the message will actually be used. |
| Lazy (guarded) logging | Checking whether the level is enabled *before* doing any formatting work, so a disabled call skips the cost entirely. |
| Bounded async logging | Offloading message formatting/writing to a background consumer via a fixed-capacity queue, with an explicit accept-or-drop decision when full — never an unbounded backlog. |
| High-cardinality label | A metrics label (e.g. a label keyed by user id or request id) with enough distinct values that per-key bookkeeping (map entries, memory) grows without a natural bound. |
| Sampled tracing | Only paying the cost of an expensive diagnostic (e.g. capturing a full stack trace) for a deterministic fraction of calls, trading complete coverage for bounded average cost. |
| Lost log count | The number of log events an async or sampling policy discarded — a number every one of this lab's overload-capable variants must report explicitly, never hide. |

## Where the cost actually is

A log statement has (at least) three separable costs, and conflating them
is the single most common observability-overhead mistake:

1. **Deciding whether to log at all** — a level or filter check. This
   should be the cheapest possible operation (a volatile-read-style
   comparison), and it must happen *before* anything else.
2. **Formatting the message** — string concatenation, `String.format`,
   boxing arguments — genuinely expensive relative to the level check,
   and entirely wasted work if the message is discarded.
3. **Delivering the message** — writing to a buffer, acquiring a lock,
   pushing to a queue, flushing to disk/network — a cost that exists only
   for messages that survive step 1.

`DISABLED_EAGER_LOGGING` and `DISABLED_LAZY_LOGGING` differ in exactly one
thing: whether step 2 happens before or after step 1's negative result is
known. This lab's benchmark shows this difference is not marginal — see
benchmark.md.

## Synchronous vs. async: where the cost moves, not whether it exists

Synchronous logging pays formatting and delivery cost directly on the hot
path, serialized through whatever lock or shared buffer the logger uses.
Async logging moves delivery (and often formatting) off the hot path onto
a queue and a background consumer — but a queue is either unbounded (this
lab's explicit anti-pattern trap, since it just relocates unbounded memory
growth under sustained overload, exactly as in the
[Backpressure](/lab/backpressure-bounded-pipelines/) lab) or bounded, in
which case it must have an explicit, counted drop policy. "Async" is not
a synonym for "free" — it is a synonym for "the cost happens somewhere
else, and there must be an honest answer for what happens when that
somewhere-else falls behind."

## Cardinality: metrics have a memory budget

A counter keyed by a low-cardinality label (e.g. `http.status_code`, a
handful of values) costs a fixed, small amount of bookkeeping no matter
how much traffic flows through it. A counter keyed by a high-cardinality
value (a raw user id, an unbounded request id) creates a new map entry per
distinct value — its memory cost grows with the *number of distinct keys
ever seen*, not with traffic volume, and can silently become the
dominant cost of "just adding a metric."

## Sampling: bounded average cost, not bounded worst case

Capturing a full stack trace on every call can be prohibitively expensive
(see benchmark.md's `sampledTracing` numbers). Sampling — only capturing
for 1 call in N, chosen deterministically — bounds the *average* added
cost to roughly `(expensive cost) / N`, while leaving every individual
sampled call exactly as expensive as capturing on every call would be.
This is a real, useful trade-off, not a way to make the expensive
operation cheap; a caller in the unlucky 1-in-N call still pays the full
cost.

## Assumptions and scope

- Every variant computes the identical hot-path checksum — instrumentation
  in this lab never changes the business result, only adds or avoids cost
  and side effects, which is the correctness invariant every test in this
  lab asserts.
- The "async bounded logging" variant's accept/drop decision is modeled
  deterministically at call time (a bounded-occupancy counter, no real
  background drain thread racing the count) so the correctness fixture
  is reproducible — see java.md/rust.md for why, and for where the real
  threaded, real-timing version is measured instead (the benchmark
  harness, not the correctness suite).
- This lab uses no external logging/metrics/tracing framework (no
  SLF4J/Logback, `tracing`, or similar) — every mechanism is implemented
  directly with `String`/`StringBuilder`/`format!`, a `ConcurrentHashMap`/
  `HashMap`, and the standard library's own stack-capture APIs
  (`Thread.getStackTrace`/`std::backtrace::Backtrace`), per this lab's
  non-goal against adding frameworks the mechanism itself doesn't need.

## Known traps

- **Benchmarking logger initialization.** Measuring the one-time cost of
  setting up a logger/metrics registry alongside the per-call cost this
  lab is actually about conflates a fixed startup cost with a per-event
  cost — this lab's harness always initializes state in `@Setup`/fixture
  construction, outside the timed region.
- **Unbounded async queue.** An "async" logger with no capacity limit just
  defers the [Backpressure](/lab/backpressure-bounded-pipelines/) lab's
  unbounded-queue anti-pattern to the logging subsystem — this lab's async
  variant is always bounded, with an explicit, counted drop policy.
- **Hiding message loss.** A dashboard that reports "logging overhead:
  low" without also reporting how many messages a bounded/sampled policy
  actually dropped is reporting an incomplete, misleadingly rosy number.
- **High-cardinality metrics without memory accounting.** Adding "just one
  more label" that happens to carry a raw, unbounded identifier can grow a
  metrics registry's memory use without bound — cardinality is a capacity
  planning question, not a code-review afterthought.

## Pre-lab diagnostic — answer

A disabled log call is only free if the *check* for whether it's enabled
happens before any other work. If the call site builds the log message
first — string concatenation, formatting numeric arguments, computing a
context object — and only afterward discards it because the level check
failed, every one of those disabled calls still pays the full formatting
cost, on every invocation, regardless of the configured level. This is
exactly the "disabled eager logging" trap this lab's benchmark
demonstrates: the log level being set to `WARN` guarantees nothing is
*written*, but says nothing about whether the *message was built* — those
are two separate costs, and only a guard placed before formatting (lazy
logging) actually eliminates the second one.
