# Incident: storage preflight checked the wrong (ephemeral) directory

**Status:** resolved (`--storage-target` decouples "where do I write" from "what filesystem must have capacity"; regression-tested)
**Affected host:** the canonical measurement host (dedicated Dell Precision 5810)
**Follow-up to:** `docs/incidents/2026-07-20-evidence-storage-exhaustion.md`

## What happened

Immediately after landing the evidence-storage-retention fix (filesystem
preflight, per-lab/per-variant storage checks, 80 GiB minimum before a
batch starts), the very next real publication batch failed before any
measurement started:

```text
failed-storage-preflight
```

for **every** lab. All checks referenced an ephemeral directory:

```text
/tmp/tmp.7C7w9UeoNr
```

The host layout:

```text
/home  -> /dev/sda2, ~295 GiB (results/ lives here)
/tmp   -> /dev/sda3, ~25 GiB
/      -> /dev/sda4
```

The real batch output root, `/home/krystian/private/kzybala-pl/results`,
had ample space. `/tmp` did not.

## Root cause

`run-all-benchmarks.sh`'s live per-lab preflight phase runs every lab
through `run-linux-evidence.sh --preflight-only` against a single, shared,
throwaway `mktemp -d` scratch directory — deliberately never touching the
real results tree for a dry pass:

```sh
"$LAB_RUNNER" "$lab" --profile "$PROFILE" --cpus ... --preflight-only --out "$PREFLIGHT_TMP" ...
```

`run-linux-evidence.sh`'s filesystem preflight (added by the prior
storage-retention fix) checked `$OUT_ROOT` unconditionally — which, during
this specific call, *was* `$PREFLIGHT_TMP`, the ephemeral `/tmp` scratch
directory, not the real results root. There was no way to tell the runner
"write your small diagnostic files here, but check capacity over there" —
`--out` served both purposes at once. Since `/tmp` (25 GiB) is a smaller,
separate filesystem from `/home` (295 GiB) on this host, the 80 GiB
minimum-before-batch check failed for every lab, even though the real
target had 200+ GiB free.

A second, related defect: the batch manifest's `sameFilesystemAsRepo`
field rendered as JSON `null`. Its computation lived far down in the
"Sequential execution" section of `run-all-benchmarks.sh`, which never
runs once live preflight (or the whole-batch storage check) has already
aborted the batch — so the field was simply never computed before the
manifest was written for this failure state.

## Fix

- **`--storage-target <dir>`** (also `PLAB_STORAGE_TARGET`) added to
  `run-linux-evidence.sh`, explicitly decoupled from `--out`. Defaults to
  `--out` when not given (preserving prior standalone behavior, where they
  were always the same directory). Explicitly validated
  (`validate_storage_target`, `lib/storage-lib.sh`) before any capacity
  check runs against it: non-empty, exists-or-creatable, writable
  (create+remove probe), resolves to a real filesystem with valid byte
  counts — validation failure is its own explicit rejection, never a
  silent fallback.
- `run-all-benchmarks.sh` now passes `--storage-target "$RESULTS_ROOT"` to
  **every** per-lab invocation — the live-preflight pass (which still
  writes small diagnostic files to the ephemeral scratch directory) and
  every real measurement invocation alike.
- The whole-batch storage/capacity preflight (minimum available,
  filesystem identity, repository/results relationship) now runs **once**,
  against the results root, before live preflight starts — and computes
  `sameFilesystemAsRepo`/`repositoryFilesystem`/`resultsFilesystem` at that
  same point, so they are available to the batch manifest regardless of
  where or whether the batch later aborts. `sameFilesystemAsRepo` is now a
  JSON boolean (`true`/`false`), never `null`, once both paths resolve —
  computed via device-id comparison (`filesystem_relationship_json`,
  `lib/storage-lib.sh`), never a fragile device-name string comparison.
- Failure diagnostics are structured and untruncated: `run-status.json`
  gained a `storageDiagnostics` object (`reasonCode`, `checkedPath`,
  `mountPoint`, `availableBytes`, `requiredBytes`, `message`); the batch
  manifest's per-lab entry gained a `preflightDiagnostics` field carrying
  the same structured content for storage-classed live-preflight failures,
  alongside (not replacing) the existing short human-readable summary
  line.

## A second real bug caught while fixing this one

`MIN_AVAILABLE_BYTES` (the 80 GiB minimum-before-batch threshold) was
being resolved via `storage_default_abort_threshold_bytes` (the 40 GiB
abort-threshold default) instead of
`storage_default_min_available_before_batch_bytes` — a copy-paste error
from the original storage-retention change. This only mattered for a
standalone `run-linux-evidence.sh` invocation without
`PLAB_MIN_AVAILABLE_BEFORE_BATCH_BYTES` explicitly exported (as
`run-all-benchmarks.sh` always does); it silently halved the minimum
before-batch requirement rather than causing an observable failure, which
is presumably why it went unnoticed until this code was touched again.
Fixed alongside the storage-target change.

## Real-host validation (not yet performed)

Per the incident-response workflow, the next real-host step is a
preflight-only dry pass confirming the fix:

```sh
./scripts/performance-lab/run-all-benchmarks.sh \
  --profile publication-core \
  --host-config config/benchmark-hosts/precision-5810.yaml \
  --repetitions 2 \
  --preflight-only
```

Expected: capacity checked against
`/home/krystian/private/kzybala-pl/results` (not `/tmp`), every lab
reports READY once other checks pass, and `sameFilesystemAsRepo: true` in
the manifest (the repository and `results/` are both under `/home` on this
host). The full publication batch is deliberately not run until this
preflight succeeds.
