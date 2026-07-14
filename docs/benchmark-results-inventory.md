# Benchmark results inventory (plab-003 task 1)

What existed before this change, established from files, not assumption.

## Manually embedded numbers

Five labs have a `benchmark.md` with a hand-authored "Measured data" table
(Java and Rust side by side, transcribed by hand from a JMH/Criterion run):
`cache-hierarchy`, `cas-contention`, `false-sharing`, `spsc-ring-buffer`,
`thread-per-core`. Two labs have no `benchmark.md` at all and therefore no
measured data to migrate: `memory-ordering`, `mesi`.

Every one of these tables' prose explicitly disclaims itself as a single,
uncontrolled, non-reproducible run ("Single developer machine ... not a
dedicated, thermally-stable rig") — the numbers were never claimed as
publication-grade, but they also have **no raw harness artifact backing
them**: no `results/<lab-id>/<run-id>/` directory, no committed JMH `-rf
json` output, no Criterion `target/criterion/` directory, anywhere in this
repository or its history. The number in the prose is the only record that
ever existed.

## Result files

`results/` is gitignored (`.gitignore`, `docs/benchmark-artifact-layout.md`)
and, as of this change, empty/absent on disk — nothing has ever been
published through the plab-002 artifact-layout convention. There is no
pre-existing structured result file (JSON, CSV) anywhere under `content/labs/`
prior to this change.

## What this means for tasks 2–9

- The importers (task 3–4) have nothing real to import yet — they are
  validated against fixtures (`scripts/benchmark-platform/results/__fixtures__/`)
  built to the documented JMH/Criterion output shapes, not against a live
  run. Their first real input will be the first `results/<lab-id>/<run-id>/`
  a maintainer actually produces per `docs/benchmark-publication-procedure.md`.
- The migration (task 9, `docs/benchmark-results-migration.md`) cannot
  produce `reproduced`/`profiled`/`verified` records for the five existing
  labs, because there is nothing to attach as a raw artifact. It produces
  `legacy-unprovenanced` records instead — an honest label, not a synthesized
  raw file.

This remains true after the 2026-07-14 remediation pass: every importer's
capability ceiling (`docs/benchmark-results-importers.md`) is registered
`"fixture-only"`, meaning no record imported by any of them — real fixture
data or otherwise — can reach `"verified"` today regardless of any other
evidence supplied (`docs/evidence-maturity.md`). Nothing in this inventory
changed; only the guarantees enforced around it did.
