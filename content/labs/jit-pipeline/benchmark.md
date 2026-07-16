# JIT pipeline — benchmark methodology

<div class="disclosure conceptual">
  <p class="disclosure-kind">Awaiting native-Linux measurement</p>
  <p>The benchmark implementation and correctness checks exist, but no
  canonical native-Linux evidence has been imported and reviewed. No
  publication-grade performance numbers are shown yet
  (<code>docs/measurement-environments.md</code>).</p>
</div>

## Experiment separation (binding)

Startup, warm-up trajectory, deoptimization events and steady state are
**never one aggregate number**:

| Experiment | Harness | What it preserves |
|---|---|---|
| Warm-up trajectory | `WarmupTrajectoryHarness` (aux — cold JVM) | per-block time series from the first invocation + compilation log |
| Deoptimization / uncommon traps | `DeoptTrajectoryHarness` (aux) | three-phase series with the transition window verbatim + compilation log |
| Steady state: call-site shape (inlining) | `JitSteadyStateBenchmark` (JMH) | mono / bi / megamorphic per-pass cost |
| Steady state: escape analysis | same benchmark, `escape` kernel | ea-on vs `-XX:-DoEscapeAnalysis` — one-flag difference, flags recorded |
| AOT baseline | Rust `aot_baseline` (separate scenario) | natively-compiled per-pass cost — never merged with JVM warm-up |

Every workload's exact totals are pinned by the shared fixture
(`code/fixtures/jit-pipeline-fixtures.json`) in both languages; a wrong
answer at any tier invalidates the run. Exact JVM flags and the per-variant
`-Xlog:jit+compilation` log are captured in each run's evidence. One
pinned worker per experiment.

## Canonical results

**Awaiting native-Linux measurement.** Collected exclusively by the unified
evidence runner on the dedicated benchmark host.

## Raw data and reproduction

```sh
# Correctness gate — must pass before any timing is trusted:
cd content/labs/jit-pipeline/code/java && mvn -q test
cd content/labs/jit-pipeline/code/rust && cargo test

# Smoke (wiring check only — zero statistical value):
cd content/labs/jit-pipeline/code/java && mvn -q -DskipTests package && \
  java -cp target/benchmarks.jar pl.kzybala.lab.jitpipeline.WarmupTrajectoryHarness \
    --blocks 2000 --calls-per-block 16

# Publication evidence (dedicated native-Linux host, normal user,
# CPU id from lscpu -e):
./scripts/performance-lab/run-linux-evidence.sh jit-pipeline \
  --profile publication --cpus <CPU_A>
```

Raw trajectory JSON, compilation logs, JMH JSON, per-variant perf stat
CSVs, worker-placement evidence and the environment manifest are preserved
per run and imported through the canonical result pipeline; evidence
maturity is derived, never stored (`docs/evidence-maturity.md`).
