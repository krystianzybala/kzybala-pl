# Coordinated Omission and Load Generation — theory

## Performance question and hypothesis

**Question:** why can a saturated system appear healthy when the load
generator waits for each response before sending the next request?

**Hypothesis:** closed-loop generators under-report latency during stalls;
open-loop or omission-corrected measurement exposes queueing and missed
schedules.

**What would disprove it:** if a closed-loop generator's recorded response
time ever exceeded the server's actual service time for that request
(implying it somehow measured queueing it structurally cannot experience),
or if an open-loop generator under sustained overload never missed a
schedule no matter how small its admission capacity (implying bounded
capacity doesn't actually bound anything), the closed-loop-hides-queueing
model taught here would be wrong.

## Learning objective

Show, with a runnable and directly falsifiable example, exactly why
"closed loop" (wait for the response, then send the next request) is
structurally incapable of measuring queueing delay: response time for a
closed-loop generator is defined as *starting only after the previous
request finished*, so it can never include time spent waiting behind a
backlog — that backlog simply doesn't exist from a closed-loop generator's
point of view, no matter how real it would be for an open-loop client.

## Prerequisites

- The [Backpressure and Bounded Pipelines](/lab/backpressure-bounded-pipelines/)
  lab — this lab's admission/missed-schedule model for open-loop variants
  is the same bounded-capacity reasoning, applied to a load generator's
  own admission decision instead of a work queue.
- The [Clocks and Latency Histograms](/lab/clocks-latency-histograms/) lab
  — background on percentile reporting this lab's corrected-recording
  variant depends on.
- Several other labs in this curriculum (the
  [SPSC Ring Buffer](/lab/spsc-ring-buffer/)'s exercises, among others)
  name coordinated omission as a trap without building it directly — this
  is the lab where the mechanism itself is the subject.

### Pre-lab diagnostic

A load test reports p99 latency of 12ms for a service under test. During
the same test, the service actually stalled for 400ms once, mid-run, while
serving a garbage-collection pause. How can a load test's reported p99 be
12ms when a real 400ms stall happened during the exact window it measured?

(Answer at the end of this page.)

## Terminology

| Term | Meaning in this lab |
|---|---|
| Closed-loop generator | A load generator that sends the next request only after receiving the response to the previous one — inherently unable to observe queueing, because it never has more than one request outstanding. |
| Open-loop generator | A load generator that sends requests on a fixed external schedule, independent of whether previous requests have completed — capable of observing real queueing, at the cost of needing an explicit admission/drop policy when the server falls behind. |
| Service time | The time a server actually spends processing one request, once it starts. |
| Response time | The total time from when a request *should* have been sent (its intended schedule) until it completes — includes any queueing delay, not just service time. |
| Coordinated omission | The measurement artifact where a closed-loop generator's stall-avoidance (waiting for each response) silently omits exactly the samples that would have shown the worst latency — the requests that *should* have been sent during a stall, but weren't, because the generator was blocked. |
| Omission correction | A post-hoc statistical technique (popularized by Gil Tene) that reconstructs the missing samples a closed-loop measurement omitted, by inferring how many requests "should" have been in flight during an observed stall and synthesizing their approximate response times. |
| Missed schedule | An open-loop generator's own honest failure mode: an intended send that could not be admitted (server backlog already at capacity) and was dropped rather than silently queued forever. |

## Why closed loop cannot see queueing, structurally

A closed-loop generator's very definition — send request *i+1* only after
receiving the response to request *i* — means request *i+1*'s measured
response time always starts at the moment request *i* completed. There is
never a moment where request *i+1* is "waiting to be sent because the
server is busy," because the generator itself refuses to send it until
the server is provably free. This is not a measurement bug that better
tooling could fix — it's a structural property of the closed-loop
protocol itself. This lab's `CLOSED_LOOP` variant makes this a literal,
checkable invariant: its recorded response time equals service time,
exactly, for every single request, because that's how the recurrence is
defined.

## Open loop: sending on schedule, regardless of server state

An open-loop generator instead commits to a schedule of intended send
times up front (a fixed rate, a bursty pattern, any deterministic or
random arrival process) and sends at those times *whether or not* the
server has finished the previous request. If the server is still busy,
the new request queues — and the time it spends queued becomes part of
its measured response time, which is exactly the number closed-loop
measurement structurally cannot produce. The cost of this honesty is that
an open-loop generator needs an explicit answer for "what if the server
falls permanently behind" (queue forever? drop? this lab uses a bounded
admission capacity and counts every drop as a "missed schedule" — see the
[Backpressure](/lab/backpressure-bounded-pipelines/) lab for the same
question asked about a work queue instead of a load generator).

## Omission correction: recovering the missing samples after the fact

If closed-loop measurement is already deployed and cannot be changed
immediately, the samples a stall silently omitted can be statistically
reconstructed: if a request took `S` total time to complete against an
intended cadence of `I`, then roughly `S/I - 1` requests *should* have
been sent (and would have been waiting) during that stall, each with a
progressively shorter apparent wait as the queue drained. This lab's
`OMISSION_CORRECTED_RECORDING` variant sends exactly like closed loop but
records these synthetic samples alongside the one real sample —
demonstrating that correction is a *post-hoc statistical patch*, not a
substitute for actually building an open-loop test when one is possible.

## Assumptions and scope

- This lab's discrete-event simulation runs on a virtual (logical) clock
  with no real sleeping or wall-clock timing — every variant's outcome
  follows from one deterministic recurrence per request, which is what
  makes correctness testable at all (a real-timing version would be
  inherently non-reproducible sample to sample).
- "Poisson-like arrivals" uses a fixed, deterministic, cyclically-repeating
  gap pattern rather than true randomly-sampled Poisson inter-arrival
  times, specifically so the correctness fixture stays exactly
  reproducible — the qualitative point (non-uniform arrivals still need
  the same open-loop admission/queueing model) holds regardless.
- The bounded server in this simulation processes exactly one request at
  a time, FIFO, non-preemptively — a simplification that isolates the
  closed-loop/open-loop measurement question from unrelated concurrency
  questions the [Backpressure](/lab/backpressure-bounded-pipelines/) and
  [MPSC](/lab/mpsc-contention/) labs cover separately.

## Known traps

- **Sleep drift without correction.** A real open-loop generator using
  `sleep(interval)` between sends accumulates drift (each sleep call has
  its own small overhead/inaccuracy), so intended send times drift further
  from the true schedule the longer the test runs — a correct
  implementation schedules against absolute intended times, not relative
  sleeps, exactly as this lab's simulation computes `intendedSendTime(i)`
  directly rather than accumulating sleeps.
- **Using offered load different from reported load.** A load generator
  that silently reduces its actual send rate under backpressure (because
  it's itself blocked) but reports the *originally intended* rate as "the
  load we tested" is reporting a number it never actually offered.
- **Dropping missed sends silently.** An open-loop generator that drops
  an intended send when its own queue is full but doesn't count or expose
  that drop is hiding exactly the information ("missed schedules") this
  lab treats as a first-class, always-reported metric.
- **Mixing service time and response time.** Reporting "average latency"
  without specifying whether it's service time (processing only) or
  response time (including queueing) makes closed-loop and open-loop
  numbers look comparable when they are measuring structurally different
  things.

## Pre-lab diagnostic — answer

The load test almost certainly used a closed-loop generator. By
definition, a closed-loop generator only ever has one request outstanding
at a time — it sends the next request only after the previous one
completes. During the 400ms GC-pause stall, the *one* request in flight at
that moment did take a long time to complete (its measured response time
would reflect something close to 400ms) — but no *other* requests were
queued behind it, because the generator never sent them; it was blocked
waiting. So instead of many requests all showing elevated latency during
the stall window (which is what would happen under real, uncoordinated
traffic), there is exactly one slow sample, diluted among thousands of
fast ones in the percentile calculation — easily invisible at p99 if the
test ran long enough. The 400ms stall was real; the measurement simply
never captured how many real users' requests would have queued behind it.
