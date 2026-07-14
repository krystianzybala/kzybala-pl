# Benchmark profiles

Canonical config lives in `content/labs/_shared/benchmark-profiles.json` and
is loaded by `scripts/benchmark-platform/profiles.js`
(`resolveProfile(name)`, `jmhArgs(profile)`, `criterionArgs(profile)`). Every
lab's JMH or Criterion run should be described as "ran the `<name>` profile,"
not as a hand-picked set of flags, so two runs claiming the same profile are
actually comparable.

Verified against the real `false-sharing` benchmark jar and Criterion bench
while writing this document (OpenJDK 26.0.1, rustc 1.88.0, Apple M1 Max).

## The four profiles

| Profile | Purpose | Rigor | Use in a `benchmark.md` table? |
|---|---|---|---|
| `smoke` | Prove the benchmark is wired and completes. | none | Never. |
| `development` | Fast feedback while changing implementation code. | indicative | Never — trend only. |
| `full` | Statistically useful evidence on a developer machine. | statistical | Yes, if the disclosure names the host and marks it non-controlled (the existing `.disclosure.measured` convention already does this). |
| `publication` | Publication-grade evidence. | controlled | Yes, only when produced on a controlled host per `docs/benchmark-publication-procedure.md`. |

`rigor` and `requiresControlledHost` are read by the comparability validator
(`scripts/benchmark-platform/comparability.js`, plab-002 task 6) — a
`publication`-labeled result without a controlled-host environment fingerprint
is flagged invalid, not silently accepted.

## JMH mapping

`jmhArgs(profile)` renders `forks`/`warmupIterations`/`warmupTime`/
`measurementIterations`/`measurementTime` as the flags JMH's own runnable
jar accepts directly:

```sh
java --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -jar target/benchmarks.jar -f 1 -wi 0 -w 200ms -i 1 -r 200ms   # smoke
```

## Criterion mapping

`criterionArgs(profile)` is a literal flag list passed after `--` to a named
bench target (Criterion's CLI parser rejects these flags when `--bench
<name>` is omitted and more than one is present, so always name the bench):

```sh
cargo bench --bench false_sharing -- --quick --noplot                # smoke
cargo bench --bench false_sharing -- --warm-up-time 3 --measurement-time 10 --sample-size 200 --noplot  # publication
```

## Macro harness

No lab in this repository uses a harness other than JMH or Criterion today
(`docs/benchmark-platform-inventory.md`). The `macro` block in each profile
(`warmupRuns`/`measuredRuns`) is the contract a future wall-clock,
end-to-end harness must follow — discard `warmupRuns` executions, then
report `measuredRuns` timed executions — so it lands with the same
profile-driven rigor from day one instead of inventing its own ad hoc
iteration count.
