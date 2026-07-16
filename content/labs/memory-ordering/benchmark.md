# Memory ordering in Java and Rust — benchmark methodology

<div class="disclosure conceptual">
  <p class="disclosure-kind">Awaiting native-Linux measurement</p>
  <p>The benchmark implementation and correctness checks exist, but no
  canonical native-Linux evidence has been imported and reviewed. No
  publication-grade performance numbers are shown yet
  (<code>docs/measurement-environments.md</code>).</p>
</div>

## Two measured halves — never mixed

- **Outcome-frequency litmus experiments** (`LitmusEvidenceHarness`): two
  persistent pinned workers iterate millions of barrier-coordinated trials
  of message-passing (MP) and store-buffering (SB) shapes at each access
  mode, counting every observed result tuple. This is a count over trials,
  deliberately NOT a JMH benchmark — rare-outcome occurrence must never be
  measured with average-time modes. **Zero forbidden observations in a
  finite run is evidence consistent with an ordering claim, never a proof
  of it**; a single forbidden observation under an ordered mode falsifies
  the implementation or the model — the asymmetry this lab teaches.
- **Operation-cost benchmark** (`MemoryOrderingCostBenchmark`, JMH): the
  per-operation price of one publication (plain payload write + flag store)
  as the flag's access mode sweeps plain → opaque → release → volatile,
  one pinned worker. This half never counts outcomes.

Records captured per litmus run: total trials, all four outcome counts,
the forbidden-outcome definition and its count, runtime/toolchain,
architecture, affinity/topology and placement evidence.

This lab publishes no Java-versus-Rust comparison: the measured subject is
the ordering mechanism itself. The Rust content examples remain
educational.

## Canonical results

**Awaiting native-Linux measurement.** Collected exclusively by the unified
evidence runner on the dedicated benchmark host.

## Raw data and reproduction

```sh
# Correctness gate — must pass before any timing is trusted:
cd content/labs/memory-ordering/code/java && mvn -q test

# Smoke (wiring check only — zero statistical value):
mvn -q -DskipTests package && \
  java -cp target/benchmarks.jar pl.kzybala.lab.memoryordering.LitmusEvidenceHarness \
    --test sb --mode opaque --trials 200000 && \
  java -jar target/benchmarks.jar 'MemoryOrderingCostBenchmark' \
    -p accessMode=release -f 1 -wi 0 -i 1 -r 200ms -w 200ms

# Publication evidence (dedicated native-Linux host, normal user,
# CPU ids from lscpu -e — distinct physical cores, no SMT siblings):
./scripts/performance-lab/run-linux-evidence.sh memory-ordering \
  --profile publication --cpus <CPU_A>,<CPU_B>
```

Raw litmus JSON, JMH JSON, per-variant perf stat CSVs, worker-placement
evidence and the environment manifest are preserved per run and imported
through the canonical result pipeline; evidence maturity is derived, never
stored (`docs/evidence-maturity.md`).
