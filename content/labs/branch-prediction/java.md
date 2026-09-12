# Branch prediction and data distribution — Java track

Package `pl.kzybala.lab.branchprediction`: deterministic dataset
generation shared with the correctness fixture
(`BranchPredictionFixtures`), one dev/wiring JMH benchmark and one
publication-evidence JMH benchmark. Both benchmarks share the exact same
data-generation and accumulation code — only the harness around them
differs (unpinned smoke vs pinned, placement-verified publication run).

## The pieces

- **Dataset generation** — `biasedValues(biasPercent, threshold, domain,
  seed, n)` builds the byte-flags and integer-thresholds datasets so the
  predicate `value >= threshold` is true with the requested probability;
  `biasedRecords` builds the mixed-hot/cold dataset the same way, deriving
  both `kind` and `payload` from the same stream value per index. All
  generation is pure integer arithmetic — no floating point, no library
  dependency — so it reproduces bit-exactly against the Rust side.
- **Four variants, one multiset per dataset.** `sortedAscending` and
  `sortedByKind` reorder the exact `random5050` arrays/records generated
  for that dataset — they never regenerate data, which is what makes the
  filtered sum invariant across `random5050`/`sorted`/`branchless`
  provable rather than assumed.
- **Two accumulation techniques** — `filteredSumBranchy` uses a real `if`
  with a side-effecting accumulation (never a ternary the JIT could lower
  to `cmov` without you noticing); `filteredSumBranchless` computes a 0/1
  arithmetic mask (`(diff >>> 31) ^ 1`) and multiplies, with no
  conditional or ternary anywhere in the method.
- **`BranchPredictionBenchmark`** (dev/wiring) — the four variants over
  the byte-flags dataset only, unpinned, for local smoke and IDE
  profiling.
- **`BranchPredictionLinuxEvidenceBenchmark`** (publication) — the full
  4×3 variant×dataset matrix, one pinned worker
  (`-Dplab.cpuA=<n> -Dplab.placementDir=<dir>`, same `WorkerPin`/
  `CpuAffinity` convention as every other lab in this repository), the
  permutation-invariance correctness oracle re-checked in `@Setup` before
  any timing, and placement evidence written to `worker-placement-*.json`.

## Build, correctness gate, run

```bash
cd content/labs/branch-prediction/code/java

# correctness gate — every fixture cell in both variant axes
mvn test

# build
mvn -q -DskipTests package

# dev smoke — all four variants, byte-flags dataset (wiring check only)
java -jar target/benchmarks.jar 'BranchPredictionBenchmark' -f 1 -wi 3 -w 1s -i 5 -r 1s -foe true

# one cell of the publication matrix, unpinned dev run
java -jar target/benchmarks.jar 'BranchPredictionLinuxEvidenceBenchmark' \
  -p variant=sorted -p dataset=mixedHotCold -f 1 -wi 2 -w 1s -i 3 -r 1s -foe true

# allocation check — the hot loop must show zero alloc/op
java -jar target/benchmarks.jar 'BranchPredictionBenchmark' -f 1 -wi 3 -w 1s -i 5 -r 1s -prof gc
```

Publication-grade numbers, `perf stat` counters (branches, branch-misses,
IPC, cycles/element) and annotated assembly come only from the
native-Linux evidence runner (benchmark.md); the commands above validate
wiring and correctness on a development machine.

## Reading the results

- Compare `random5050` against `sorted` on the **same dataset** first —
  it isolates the predictability effect from everything else, because the
  filtered sum (and therefore the guarded work) is identical.
- Compare `random5050` against `branchless` second — a genuine branchless
  win means lower ns/element *and* `branch-misses` collapsing toward the
  branchless run's baseline (which still executes branches for loop
  control, just none that depend on the data).
- `biased9010` sits qualitatively between `random5050` and `sorted`: more
  predictable than a coin flip, less than a single run — use it to check
  that the misprediction cost scales with predictability rather than
  jumping straight from "bad" to "perfect".
