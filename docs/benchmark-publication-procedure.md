# Publication procedure (controlled host)

A `publication`-profile number (`docs/benchmark-profiles.md`) is only
evidence when it comes from a designated controlled host, run through this
procedure — never from `ubuntu-latest` or any other shared CI runner
(`design.md`'s CI policy; `.github/workflows/ci.yml`'s `benchmark-smoke`
job runs `smoke` only, for exactly this reason).

## 1. Prepare the host

- Use a machine you control exclusively for the run's duration — no other
  CPU-bound work, no video calls, no background builds.
- Where the OS exposes it (Linux: `cpupower frequency-set -g performance` or
  writing `performance` to each `scaling_governor`), pin the CPU governor to
  a fixed, non-variable state. **Only if capability-detected** — do not
  assume a governor exists; `scripts/benchmark-platform/environment.js`'s
  `powerManagement` field reports `"not-applicable"` on macOS/Windows and
  `"unavailable"` on a Linux host without a `cpufreq` driver. Record whatever
  it reports; do not invent a value.
- If your platform supports CPU/thread affinity pinning for the benchmark
  process (`taskset` on Linux), use it, and record that you did.
- Let the machine idle for a minute or two after prep so any pinning takes
  effect before the correctness gate runs.

## 2. Run the correctness gate

```sh
node scripts/benchmark-platform/run-correctness-gate.js <lab-id>
```

A `"blocked"` result (exit code 1) means: stop. Fix the failing test before
running anything for publication. A `"gap"` result (no tests for a
language) means: the number you are about to publish is not gated by
anything real for that language — say so explicitly in the lab's
`benchmark.md` disclosure rather than implying full coverage.

## 3. Run the publication profile

```sh
# Java/JMH
cd content/labs/<lab-id>/code/java
java --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -jar target/benchmarks.jar \
  $(node ../../../../../scripts/benchmark-platform/print-profile-args.js publication jmh) \
  -rf json -rff results.json

# Rust/Criterion
cd content/labs/<lab-id>/code/rust
cargo bench --bench <bench-name> -- \
  $(node ../../../../../scripts/benchmark-platform/print-profile-args.js publication criterion)
```

Criterion's raw per-sample data and HTML report land in `target/criterion/`
automatically; JMH's `-rf json -rff results.json` is what produces its raw
per-iteration file — both are required, not optional, for a publication
number (spec.md "Raw result preservation").

## 4. Capture environment metadata

```sh
node scripts/benchmark-platform/capture-env.js > env.json
```

Manually add `"controlledHost": true` to the captured JSON before storing
it — this is a human attestation that step 1 actually happened; it is
never inferred automatically (`comparability.js`'s `validateProfileClaim`
rejects a `publication`-labeled run without it).

## 5. Compute the run id and lay out the artifact

```sh
node scripts/benchmark-platform/write-run-artifact.js <lab-id> publication
```

This prints the `results/<lab-id>/<run-id>/` directory
(`docs/benchmark-artifact-layout.md`) it created, with `meta.json` already
populated. Copy the JMH `results.json` and the relevant
`target/criterion/**/{raw.csv,estimates.json}` files into its `raw/`
subdirectory.

## 6. Check comparability before writing the number down

For a genuine Java-vs-Rust comparison, run
`scripts/benchmark-platform/comparability.js`'s `validateComparability`
against both languages' run descriptions (build mode, dataset id, shared
semantics-fixture hash, warm-up config). A `"non-comparable"` or
`"invalid"` result means: report the numbers separately with that label, do
not present them as a fair comparison.

## 7. Publish

- Commit the `results/<lab-id>/<run-id>/` directory (it is otherwise
  gitignored — see `.gitignore`) as the evidence backing the new number.
- Update the lab's `benchmark.md` `.disclosure.measured` block with the
  real host/toolchain facts from `env.json` (not hand-typed guesses) and
  link the committed `raw/` directory, following the existing convention in
  e.g. `content/labs/false-sharing/benchmark.md`.
- Never round or "clean up" a number in the prose beyond what the raw file
  actually reports (`proposal.md`'s completion evidence: "No benchmark
  number is fabricated, manually rounded into source, or presented without
  provenance").
