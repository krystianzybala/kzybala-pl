# Coordinated Omission and Load Generation — exercises

Attempt each exercise before opening the collapsed hints/solutions, and
verify against the stated success criteria.

## Exercise 1 — Diagnosis (the load test that lied about capacity)

A team's closed-loop load test reports their API sustains 5,000 req/s
with p99 latency of 15ms. In production, at an actual offered load of
5,000 req/s, p99 latency is 400ms and climbing.

**Task:** explain why a closed-loop test's "5,000 req/s" figure can be
fundamentally different from a real 5,000 req/s of offered load, and name
the variant from this lab that would have caught the discrepancy before
production.

**Success criteria:** you explain that a closed-loop generator's
throughput is an *achieved* rate — it can never send faster than the
server can respond, because it waits for each response — so "5,000 req/s"
in the test measures the server keeping up, by construction, not the
server surviving an *offered* 5,000 req/s that arrives regardless of
whether it's keeping up. You correctly identify `OPEN_LOOP_FIXED_RATE`
(or any open-loop variant) as the one that would have revealed the gap,
because it commits to sending 5,000 req/s regardless of server state.

<details>
<summary>Hint</summary>

If the server actually degrades to sustaining only 3,000 req/s under
real production conditions, what does a closed-loop generator's own
throughput number automatically become — and does it ever report the
5,000 that was originally intended?

</details>

<details>
<summary>Solution</summary>

A closed-loop generator's reported throughput is always *whatever rate
the server actually achieved*, because the generator's own sending rate
is entirely gated by server response — it is definitionally impossible
for a closed-loop test to measure "offered load exceeds capacity," because
the generator never offers more than the server can currently return. If
the server can only sustain 3,000 req/s under real conditions, a
closed-loop test run against it will report roughly 3,000 req/s achieved,
looking successful — it silently redefines "the load we tested" down to
whatever the server could handle, with no signal that a 5,000 req/s
target was even attempted. An `OPEN_LOOP_FIXED_RATE` test committed to an
intended 5,000 req/s schedule, by contrast, would show the server falling
behind explicitly — the queue backlog would grow, response times would
climb (visible immediately, not hidden), and missed-schedule counts would
appear once the bounded admission capacity was exhausted. This is exactly
this lab's design.md trap "using offered load different from reported
load": the fix is reporting both the *intended* (offered) rate and the
*achieved* rate as separate, explicit numbers, which only an open-loop
test structure can make meaningful.

</details>

## Exercise 2 — Implementation (a percentile histogram, corrected and uncorrected)

This lab's `Outcome` reports only aggregate totals (`totalResponseTimeNanos`,
`maxResponseTimeNanos`) — not a full distribution.

**Task:** extend (a copy of) the closed-loop and omission-corrected
recurrences to collect every individual response-time sample (real and
synthetic) into a list, then compute p50/p99/p999 for both the raw
closed-loop samples and the corrected sample set on the same
`periodicTenMsStall` dataset.

**Success criteria (measure, don't assert):**

1. p50 is nearly identical between raw and corrected (most samples are
   unaffected by the rare stall).
2. p99 and especially p999 differ meaningfully between raw and corrected —
   the corrected set's tail should be visibly worse, because it now
   includes the synthetic samples the stall was hiding.
3. You can explain, in one sentence, why p50 barely moves while p99/p999
   move a lot: what does that tell you about *where* coordinated omission
   actually distorts a distribution?

<details>
<summary>Hint</summary>

Coordinated omission adds samples, it never removes any — so every
percentile at or below "mostly unaffected" stays put, while percentiles
near and past the fraction of samples that *are* affected shift
substantially. Where in the distribution do 119 stall occurrences out of
600 + (119 × 9 synthetic) total samples actually land?

</details>

## Exercise 3 — Evidence interpretation (two histograms, one root cause)

Below is a **synthetic teaching example** in HdrHistogram-style
percentile-table format — constructed for this exercise, not captured
from any run. It is educational material for practicing evidence
interpretation only: it is never used as measurement evidence, never
supports this lab's performance conclusions, and never enters a
comparison or maturity calculation. Two reports from the *same*
underlying system and the *same* stall pattern, one closed-loop, one
open-loop, labels removed:

```
Report A                        Report B
50.000%      9 us               50.000%      9 us
90.000%     14 us               90.000%     14 us
99.000%     18 us               99.000%     41 us
99.900%     22 us               99.900%    812 us
99.990%     26 us               99.990%  9,105 us
Max         31 us               Max     11,220 us
```

**Task:** decide which report used a closed-loop generator, and justify it
using the mechanism this lab builds directly — not just "the tail is
bigger." Then state one thing this pair of histograms **cannot** tell you
about which report is "correct."

**Success criteria:** your identification is correct (Report A is
closed-loop) and your reasoning explains *why* structurally, not just
descriptively; your "cannot tell you" statement recognizes that neither
histogram alone proves what the real end-user experience was without
knowing which measurement method matches how real traffic actually
arrives (open-loop-like or closed-loop-like).

<details>
<summary>Solution</summary>

**Report A is closed-loop.** A closed-loop generator can never record a
response time longer than one server stall's actual duration for exactly
one sample — the flat tail (Max of 31 µs, under 4x the median) is the
signature this lab's own `closedLoopMaxResponseTimeEqualsStallMagnitude`
test asserts directly: whatever the worst single stall was, that's the
worst *sample*, and it is diluted among every other fast sample at the
percentile level because only one request was ever "in flight" during it.
Report B's dramatic tail growth (Max of 11,220 µs, roughly 1,400x the
median) is the open-loop (or omission-corrected) signature: multiple
samples reflecting the queue that built up during the same stall, each
showing a large but decreasing wait as the backlog drained — exactly the
shape `OMISSION_CORRECTED_RECORDING`'s synthetic-sample generation
produces.

**What this pair cannot tell you:** which report is "correct" in an
absolute sense depends on how real traffic actually arrives at this
system. If real clients genuinely behave in a closed-loop fashion (each
one waits for its own response before making another request, and there
is no queue of *other* clients' requests backing up behind a slow one),
Report A's numbers may be closer to real individual client experience.
If real traffic is open-loop-like (many independent clients, a fixed
external request rate, requests queuing behind each other during a
stall — the far more common production shape), Report B is closer to
reality. The histograms alone don't disclose which traffic model applies
to real users — that has to come from understanding the actual system,
not from the numbers.

</details>
