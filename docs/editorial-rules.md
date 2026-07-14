# Editorial rules for the Performance Lab curriculum

These rules apply to every lab in `assets/data/curriculum.json`, whether
`planned` or `verified`. They exist so the curriculum can grow to 36+ labs
without drifting into the "collection of disconnected benchmark pages"
`plab-001-performance-lab-foundation/proposal.md` was written to prevent.
This document is the curriculum-level companion to `docs/components.md`
("Benchmark disclosure") — that doc defines the markup contract; this one
defines the judgment calls a lab author has to make before reaching for it.

## No fabricated numbers

- Never invent a benchmark number, round one "for a cleaner chart," or
  publish a number without the run that produced it.
- A lab with no measured result yet states that plainly (`curriculumStatus:
  "planned"` or `"partial"`, `evidenceMaturity: null`) — it does not show a
  placeholder chart with plausible-looking numbers. A reader cannot tell a
  placeholder from a real result by looking at a chart; the honesty has to
  live in the surrounding text and status, not in the visual.
- `durationMinutes` in `curriculum.json` is always an authoring estimate for
  planned/partial entries, never a measurement — see
  `docs/curriculum-manifest.md`.

## Neutral comparison (spec.md "Neutral comparison")

When a lab shows both a Java and a Rust result:

1. **State what is equivalent first.** Same algorithm, same data structure
   shape, same concurrency strategy. If they diverge, say so before showing
   any number — see `design.md`'s "Java and Rust examples must implement
   equivalent semantics before their numbers are compared."
2. **Name the toolchain and settings for both.** JDK/rustc version, JIT
   tier or `--release` flags, GC or allocator, warmup — the
   `.disclosure.measured` block (`docs/components.md`) is where this goes.
3. **State what cannot be concluded.** A single-machine, single-run number
   is a shape, not a portable claim (see `content/labs/false-sharing/benchmark.md`
   for the pattern this site already follows). Explicitly rule out the
   reading a screenshot-hungry reader will reach for: "language X is
   faster."
4. **Never rank languages from one number.** No lab concludes "Java wins" or
   "Rust wins." A lab may show that a specific technique has a larger or
   smaller effect in one runtime than the other — that is a statement about
   the technique and the runtime's mechanics, not a verdict on the language.

## Mean latency is not sufficient

Any latency-sensitive conclusion needs a distribution, not just a mean —
`design.md`: "Mean latency alone is not sufficient for latency-sensitive
conclusions." Report percentiles (p50/p99/p99.9) or show the histogram
shape when latency (not throughput) is the point of the lab. This is the
job of `plab-102-clocks-latency-histograms` and the provenance pipeline
(`plab-003-results-provenance-publication`) at the tooling level; at the
editorial level, a lab author must not paste a bare mean into prose and
call it "the latency."

## Client-side benchmarks are demonstrations, not evidence

Any benchmark that runs in the reader's own browser (vs. a pre-recorded
JMH/Criterion result) is an educational demonstration only, per `design.md`.
Label it as such next to the number — the reader's browser, tab count, and
throttling state are not a controlled environment, and the number will
change from reader to reader.

## Evidence maturity is earned, not assigned

`evidenceMaturity` (`draft` → `reproduced` → `profiled` → `verified`,
`docs/curriculum-manifest.md`) only moves up when the corresponding work has
actually happened — a lab does not get marked `verified` because it "should
be fine." No entry in the current manifest is `verified`; that tier is
reserved for the provenance pipeline in `plab-003`.

## Trade-offs, not verdicts

Every lab that recommends a technique shows both when to use it and when
not to (`docs/components.md`, "Trade-offs" component) — a technique that
helped in one lab's benchmark is not a universal recommendation.
