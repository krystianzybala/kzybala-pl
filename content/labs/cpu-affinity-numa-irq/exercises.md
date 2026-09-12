# CPU Affinity, NUMA and IRQ Placement — exercises

Attempt each exercise before opening the collapsed hints/solutions, and
verify against the stated success criteria.

## Exercise 1 — Diagnosis (the "optimization" that only worked once)

An engineer pins their hot-path worker thread to CPU 3 with
`taskset -c 3`, benchmarks a 2x improvement, ships it. Two weeks later, on
a different server in the same fleet, the identical pinned deployment is
*slower* than the unpinned baseline was.

**Task:** name the most likely reason hardcoding `cpu=3` produced opposite
results on two "identical" servers, and state what this lab's `Topology`
probe does specifically to avoid the mistake.

**Success criteria:** you identify that CPU 3 is not the same *kind* of
CPU on every host — it might be an isolated, otherwise-idle core on one
machine and an SMT sibling of a busy IRQ-handling core, or a CPU on a
remote NUMA node relative to the thread's data, on another — and that
"CPU 3" carries no topology information by itself. You correctly describe
this lab's approach (probe `/sys` for the *role* — isolated core, SMT
sibling, same/remote NUMA node — on the current host, rather than
hardcoding an id) as the fix.

<details>
<summary>Hint</summary>

What does the engineer's `taskset -c 3` command actually know about CPU 3
on the second server — its SMT grouping? Its NUMA node? Whether anything
else is pinned there?

</details>

<details>
<summary>Solution</summary>

`taskset -c 3` communicates nothing about *why* CPU 3 was chosen — it
only works on the first server if CPU 3 happens to be, say, an isolated
core with no IRQ traffic and local access to the thread's memory. On a
second server with a different core layout, socket count, or IRQ routing
configuration, CPU 3 could be an SMT sibling of the busiest core in the
system, or on a NUMA node remote from wherever the thread's data actually
lives — the same command produces an entirely different *placement role*.
This is exactly why this lab's `Topology.detect()` never hardcodes an id:
it discovers, per host, which CPU plays which *role* (isolated, SMT
sibling of a specific core, same-node, remote-node) by reading real
`/sys` topology files, so the same source code asks for "an isolated
core" or "the same NUMA node as this data" on every host, and gets a
different concrete CPU id where appropriate — the abstraction that
survives a fleet with heterogeneous hardware is the *role*, never the
number.

</details>

## Exercise 2 — Implementation (measure your own machine's topology)

This lab's `Topology.detect()`/`detect_topology()` only ever runs on this
repository's macOS development host during local development, where it
always reports `supported = false`.

**Task:** if you have access to a Linux machine (or a Linux VM/container
with `/sys` mounted), run this lab's correctness test suite there and
report what `Topology.detect()` actually discovers — how many CPUs, an
isolated-core id, whether an SMT sibling was found, and whether more than
one NUMA node exists.

**Success criteria (measure, don't assert):**

1. The correctness suite still passes on that host (every checksum
   invariant holds regardless of what topology was discovered).
2. You report the actual discovered topology (or explicitly report "single
   NUMA node, no SMT" if that's what the host has — a negative result is a
   valid, useful result here).
3. You can explain why a container without `/sys/devices/system/node`
   mounted (common in some sandboxed environments) would cause this lab's
   topology probe to report `remote_numa_cpu: None` even on physically
   multi-node hardware — and why that is the *correct* behavior (not a
   bug) per this lab's "mark evidence as unavailable rather than fabricate"
   policy.

<details>
<summary>Hint</summary>

`cat /proc/cpuinfo | grep processor | wc -l`, `lscpu`, and
`ls /sys/devices/system/node/` (if present) will get you most of what you
need to compare against what this lab's own probe reports.

</details>

## Exercise 3 — Evidence interpretation (perf stat counters across placements)

Below is a **synthetic teaching example** in `perf stat`'s output format —
constructed for this exercise, not captured from any run. It is
educational material for practicing counter interpretation only: it is
never used as measurement evidence, never supports this lab's performance
conclusions, and never enters a comparison or maturity calculation. Two
runs of the identical memory-scan workload, one pinned to the same NUMA
node as its data, one pinned to a remote node, labels removed:

```
Run A:
     8,204,113,552      cycles
     6,991,882,004      instructions              #    0.85  insn per cycle
        14,203,881      cache-misses
             8,442      cpu-migrations

Run B:
     8,190,447,221      cycles
     3,102,556,918      instructions              #    0.38  insn per cycle
       198,340,552      cache-misses
                17      cpu-migrations
```

**Task:** decide which run is the remote-NUMA placement, and justify it
from at least two separate counters. Then state one conclusion these
numbers **cannot** support.

**Success criteria:** your identification is correct, your reasoning
names the mechanism (remote memory access latency stalling the pipeline,
visible as lower IPC despite an almost-identical cycle budget) rather than
just citing that a number is bigger, and your "cannot conclude" statement
is genuinely unsupported by this data.

<details>
<summary>Solution</summary>

**Run B is the remote-NUMA placement.**

- Both runs spent almost the same number of cycles (8.20B vs. 8.19B) —
  the same measurement window — but Run B retired less than half the
  instructions (3.10B vs. 6.99B), for an IPC of 0.38 vs. 0.85. Something
  is stalling the pipeline in Run B far more often per cycle.
- `cache-misses` is ~14x higher in Run B (198M vs. 14.2M) — consistent
  with a memory-bound scan whose data lives on a remote node: every miss
  that would have been a fast local-memory fetch is instead a slower
  cross-node fetch, and a scan that touches every element will generate
  proportionally more of these misses being *costlier*, not just present.
- `cpu-migrations` is low and similar in both runs (8,442 vs. 17 — if
  anything, Run A shows more migration, which argues *against* attributing
  A's behavior to instability) — this rules out "Run A was just unstably
  scheduled" as the explanation and points back to the placement
  difference itself.

**What this data cannot support:** the exact remote-access latency in
nanoseconds, or that this specific ratio (roughly 2.2x fewer instructions
retired per cycle) generalizes to any other workload or any other pair of
NUMA nodes — cache-miss cost depends heavily on the specific memory access
pattern, and this is one synthetic pair of counters for one workload
shape, not a general remote-vs-local NUMA cost model.

</details>
