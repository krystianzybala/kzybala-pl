# Cache coherence and MESI — benchmark methodology

<div class="disclosure conceptual">
  <p class="disclosure-kind">Awaiting native-Linux measurement</p>
  <p>The benchmark implementation and correctness checks exist, but no
  canonical native-Linux evidence has been imported and reviewed. No
  publication-grade performance numbers are shown yet
  (<code>docs/measurement-environments.md</code>).</p>
</div>

## Simulated model vs measured hardware — a hard separation

This lab has two deliberately separate halves:

- the **interactive MESI simulator** on the lab page is an educational
  model of protocol states and transitions. It is teaching material,
  never measurement evidence, and nothing measured is ever back-projected
  onto it as "the line was in state M here";
- the **measured coherence scenarios** below observe real costs with
  hardware counters. Counters and `perf c2c` support coherence-traffic
  and ownership-transfer conclusions (invalidations, cache-to-cache HITM
  transfers); they do not support claims about the exact MESI state of a
  specific line at a specific moment, and this lab never makes such
  claims.

## Measured scenarios (Java, two pinned workers)

One operation = one atomic increment (or one consumed read for reader
roles) on an `AtomicLongArray` slot; slots X and Y are ≥ 256 bytes apart.
Exact-count semantics are the correctness oracle (suite runs before any
timing).

| Scenario | Worker A | Worker B | What it isolates |
|---|---|---|---|
| `singleWriter` | writes X | thread-local only | exclusive-ownership write baseline |
| `sharedReaders` | reads X | reads X | Shared-state reads, no invalidation |
| `writerInvalidation` | writes X | reads X | write→read-miss invalidation traffic |
| `pingPong` | writes X | writes X | maximal ownership transfer |
| `paddedLines` | writes X | writes Y | two-writer control, no shared line |

Placement: two distinct physical cores, same socket/NUMA node, each worker
pinned via `sched_setaffinity` with verified placement
(worker-placement evidence; `docs/linux-evidence-runner.md`). `perf c2c`
evidence is required for this lab. This lab is Java-only on the measured
side — the mechanism is runtime-independent and the cross-language story
belongs to the false-sharing lab's equivalence contract.

## Canonical results

**Awaiting native-Linux measurement.** Collected exclusively by the unified
evidence runner on the dedicated benchmark host.

## Raw data and reproduction

```sh
# Correctness gate — must pass before any timing is trusted:
cd content/labs/mesi/code/java && mvn -q test

# Smoke (wiring check only — zero statistical value):
mvn -q -DskipTests package && \
  java -jar target/benchmarks.jar 'MesiLinuxEvidenceBenchmark' \
    -p scenario=pingPong -t 2 -f 1 -wi 0 -i 1 -r 200ms -w 200ms

# Publication evidence (dedicated native-Linux host, normal user,
# CPU ids from lscpu -e — distinct physical cores, no SMT siblings):
./scripts/performance-lab/run-linux-evidence.sh mesi \
  --profile publication --cpus <CPU_A>,<CPU_B>
```

Raw JMH JSON, per-variant perf stat CSVs, `perf c2c` recordings/reports,
worker-placement evidence and the environment manifest are preserved per
run and imported through the canonical result pipeline; evidence maturity
is derived, never stored (`docs/evidence-maturity.md`).
