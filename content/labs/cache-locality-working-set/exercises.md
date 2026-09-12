# Cache locality and working-set size — exercises

## Exercise 1 (diagnosis): the "random access is fine" claim

A colleague benchmarks two loops over the same 128 MB array: one visits
elements via a shuffled index list (each lookup independent of the last),
the other via a linked-list-style pointer chase over the identical
values. Both are "random access," and the colleague concludes they should
cost about the same. The pointer chase measures roughly 20× slower.
Diagnose why "random" was not enough information, and name the specific
hardware capability that explains the entire gap.

**Success criteria:** you name that the shuffled-index loop's loads are
mutually *independent* (address doesn't depend on a prior value), while
the pointer chase's loads are *dependent* (`idx = next[idx]`); you name
memory-level parallelism (MLP) as the mechanism — independent misses can
overlap in flight, dependent misses cannot, each paying its full latency
serially; and you point at this lab's `random` vs. `pointerChase`
variants over the identical `values[i]=i` payload as the controlled
demonstration of exactly this gap.

<details>
<summary>Hint</summary>

"Random" describes the *pattern* the prefetcher sees; it says nothing
about whether consecutive loads can be issued at the same time. Ask: does
knowing load N's result change what address load N+1 targets?
</details>

## Exercise 2 (implementation): find your own staircase

Using `CacheTopology`/topology detection on your own machine (dev
wiring, not publication), run `CacheLocalityLinuxEvidenceBenchmark`'s
`sequential` variant (or the Rust `cache_locality_evidence` binary)
across all five `workingSet` values and record the reported element
counts and your machine's ns/element for each. Predict, before running,
at which working-set tier you expect the biggest single jump, given your
own `/sys/devices/system/cpu/cpu0/cache` (or your platform's equivalent)
sizes.

**Success criteria:** you report the actual byte sizes your topology
detection resolved for each tier (or that it fell back to the documented
development sizes, if not on Linux); your prediction names a specific
tier boundary (e.g., "L2→LLC") before you look at the numbers; and you
state whether your measured ns/element shows a clear step at that
boundary or a smoother ramp, with one plausible explanation either way
(smoother ramps often mean the "L1/L2/LLC" sizes used for scenario
selection don't isolate a single level cleanly on your specific
microarchitecture — SMT sibling contention and non-inclusive cache
hierarchies are common causes worth naming, not assuming away).

<details>
<summary>Hint</summary>

`CacheTopology.detect()` (Java) / `detect_topology()` (Rust) print
`detected: false` and fall back to 32 KB/1 MB/32 MB if your platform
doesn't expose `/sys/devices/system/cpu/cpu0/cache` — check this before
trusting your own boundary prediction.
</details>

<details>
<summary>Solution</summary>

The biggest single jump is usually LLC→DRAM (the `llc`→`2xllc` or
`2xllc`→`large` transition), because DRAM latency is typically 4-10× a
last-level-cache hit rather than the roughly 2-4× step between L1/L2/LLC
— consistent with the memory-hierarchy diagram in theory.md. If your
result instead shows the biggest jump at L1→L2, suspect the `l1`
working-set tier wasn't actually small enough to *stay* in L1 once
warm-up churn and any co-resident data (JVM/runtime state) are accounted
for — check the reported element count against your real L1d size, not
just the nominal tier name.
</details>

## Exercise 3 (evidence interpretation): read the perf counters, not the label

Below is the shape of two cells' `perf stat` output from this lab's
publication runner (illustrative structure, not real captured evidence):

```text
variant=sequential workingSet=large
  cache-references: 2,145,881,204   cache-misses: 1,998,412,050  (93.1%)
variant=blocked     workingSet=large
  cache-references: 2,151,004,662   cache-misses:   214,331,908  (10.0%)
```

Answer from these two lines alone: (a) both variants visit the same
number of elements against the same `large` working set — does a near-
identical `cache-references` count between them make sense, and why; (b)
does the ~9× gap in `cache-misses` percentage support or undermine this
lab's claim that blocking recovers locality for a naturally unfriendly
traversal; (c) a colleague argues "93% miss rate means sequential access
is broken here" — name the one fact about the `large` working set that
makes a near-100% miss rate the *expected*, not broken, result for
`sequential` at that specific tier.

**Success criteria:** (a) yes — both variants touch every element of the
same dataset exactly once, so the total number of cache-line-worth
references should be close regardless of traversal order; only the *hit
rate* against those references depends on order; (b) strongly supports
it — a 93% vs. 10% miss rate on the identical reference count is
precisely the mechanism theory.md predicts: blocking keeps each tile's
working set inside a fast level long enough to be mostly served from
cache, while a full column-major-equivalent pass would not; (c) the
`large` tier is deliberately sized to exceed the LLC (8× LLC capacity, by
this lab's `CacheTopology` formula) specifically so that a "no-locality-
help" traversal like `sequential`'s worst-case counterpart *cannot* be
serviced from any cache level — a near-100% miss rate there is the
expected floor, not a defect, and is exactly why this tier exists in the
matrix.

<details>
<summary>Hint</summary>

Re-read what `large` is *for* in this lab's dataset matrix (theory.md's
staircase table) before deciding whether a given miss rate at that tier
is surprising.
</details>
