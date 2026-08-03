# Incident: manifest generator declared artifact paths unconditionally, failing 18 genuinely-good runs (`batch-20260727T121031Z`)

**Status:** resolved (six-state component model in the manifest generator and
verify-evidence.sh, `reindex-evidence.sh` repair tool, `verify-benchmark-batch.sh`
integrity/acceptance split landed; regression-tested)
**Affected batch:** `batch-20260727T121031Z` on the canonical measurement host
**Affected labs/runs:** 18 runs across the reference tier, all `verification-failed`

## What happened

A full publication batch ran to completion on the 5810 — every JMH invocation,
perf stat capture, perf c2c profile, Rust harness run and worker-placement
check finished cleanly, with no timeouts, no rejections, no host instability.
Despite that, 18 runs came out of `verify-benchmark-batch.sh` as
`verification-failed`, reason code `manifest-references-missing-artifacts`.

Inspecting the failing runs on disk showed every artifact the underlying
measurement was actually supposed to produce was present and intact. The
"missing" paths `verify-evidence.sh` complained about were for components
that variant genuinely never scheduled in the first place — e.g. perf c2c for
a variant whose profiler policy is `stat`-only, or a Rust-harness artifact for
a lab with no Rust harness at all.

## Root cause

`evidence-manifest.json`'s `variants` object was generated from a fixed
per-variant template: every variant entry unconditionally declared a path for
`jmh.json`, `perf-stat.csv`, `perf-c2c-report.txt`, `rust-evidence.json`, etc.,
regardless of whether that component's profiler policy, variant kind, or lab
capability meant it was ever going to run. `verify-evidence.sh` then checked
every declared path for existence with no notion of "this component was never
supposed to produce anything" — a component that was correctly, deliberately
skipped looked identical to one whose output genuinely went missing.

This is the flip side of the earlier profiler-policy work
([[performance-lab-evidence-workflow]]: `stat`/`c2c`/`c2c-core-only` per-variant
gating, smoke/publication-sweep never running c2c): once policy could decide
NOT to run a component, the manifest generator and the verifier were never
updated to agree on how "not run by policy" should be represented. The
generator kept writing a path anyway; the verifier kept requiring it to
exist.

## Fix

- **Six-state component model.** Every component of a variant
  (`jmh`, `auxHarness`, `perfStat`, `perfC2c`, `rustHarness`,
  `workerPlacement`) is now exactly one of:
  `completed | not-scheduled | not-applicable | failed | timed-out | retained-summary-only`.
  `not-scheduled` (policy chose not to run it this profile/variant) and
  `not-applicable` (this variant/lab structurally cannot have this component
  — e.g. no Rust harness, aux-kind variant has no JMH) declare **zero**
  required artifact paths. `completed`/`retained-summary-only` are the only
  states that require anything to exist on disk, and are validated against
  the real filesystem (`assert_completed_artifact`) **before** the manifest
  is finalized in `run-linux-evidence.sh` — a missing artifact for a
  component the code itself just marked "completed" now aborts the run
  immediately as a manifest-generation defect, rather than silently writing
  a dangling path that only surfaces later in a separate verify step.
  `perfC2c`'s `retained-summary-only` state (raw `perf-c2c.data` deleted by
  the retention policy, bounded report kept) is distinguished from
  `completed` (raw file retained) via the existing retention record, never
  recomputed.
- **`verify-evidence.sh`** rewritten to be component-status aware: it checks
  artifacts required by each component's actual declared status, rejects any
  unknown status string, and independently re-checks the
  `dirtyTree=true` + `publicationEligible=true` provenance invariant rather
  than trusting the generator's own claim.
- **`verify-benchmark-batch.sh`** split into `--integrity-only` (archive
  extraction, hashes, manifest/archive structure — can pass even for a
  batch where every run was legitimately rejected) and `--strict` (adds
  evidence acceptance: every requested repetition collected, every per-lab
  archive re-verifies, no `verification-failed`/`rejected-or-failed` runs,
  provenance invariants hold). The bare no-flag form is now a deprecated,
  explicitly-warned alias for `--integrity-only` — it used to be the only
  mode and silently conflated "the archive is well-formed" with "this
  evidence is good enough to import."
- **`import-benchmark-batch.sh`** now always calls `--strict`: importing is
  exactly the action evidence-acceptance gates, so archive integrity alone
  was never sufficient grounds to import a partial or rejected batch.
- **`reindex-evidence.sh`** (new): a repair tool for exactly this failure
  mode — an already-collected batch whose manifests were written by the
  buggy unconditional-template generator. It re-derives each run's
  `variants` object from (a) the lab's real resolved execution policy for
  that variant and (b) the artifacts genuinely present on disk, using the
  same six-state model, then re-verifies with the current
  `verify-evidence.sh`. It **never re-runs a measurement** and **never
  fabricates a missing artifact** — a component the lab would have
  scheduled but whose file is genuinely absent stays `failed`, so a run
  is only reclassified `collected` in the derived batch manifest if it now
  genuinely, independently re-verifies. It always writes to a new
  `--output-dir`; the original batch directory is never modified (tested:
  byte-identical file hashes before/after).

## Real-host validation (not yet performed)

The 18 affected runs in `batch-20260727T121031Z` have not yet been repaired
on the actual measurement host. The next real-host step is:

```sh
./scripts/performance-lab/reindex-evidence.sh \
  --batch-dir results/batches/batch-20260727T121031Z \
  --output-dir results/repaired/batch-20260727T121031Z-r1 \
  --dry-run
```

confirming the plan looks right, then rerunning without `--dry-run` and
checking the derived batch with
`verify-benchmark-batch.sh --strict <derived archive>` before import.
