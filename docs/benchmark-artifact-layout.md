# Raw artifact layout and run ids

`spec.md`'s "Raw result preservation" requirement: the original harness
output and environment metadata behind any published number must remain
available, unrounded and untouched. This document defines where that lives
and how its directory name is derived.

## Run id

`scripts/benchmark-platform/run-id.js` (`computeRunId`) hashes:

```
{ labId, implementationRevision, toolchain, profile, params, environment }
```

with recursively key-sorted JSON (`stableStringify`) through SHA-256,
truncated to 16 hex characters. `implementationRevision` is the short git
hash of the last commit touching `content/labs/<id>/code/`
(`resolveImplementationRevision`), so a code change always produces a new
run id — no hand-picked run number, no accidental collision between two
different implementations that happen to share a profile name.

Verified while writing this document: the same logical input produces the
same id regardless of object-key order; changing any single field (e.g.
`profile: "smoke"` → `"full"`) changes the id.

## Directory layout

```
results/<lab-id>/<run-id>/
  meta.json     # labId, implementationRevision, toolchain, profile, params,
                # environment (scripts/benchmark-platform/environment.js
                # output), comparability label (task 6), capturedAt
  raw/          # untouched harness output:
                #   JMH: -rf json output file(s)
                #   Criterion: target/criterion/**/{raw.csv,estimates.json}
```

`results/<lab-id>/<run-id>/` is **immutable once written**: a re-run gets a
new run id (even a no-op re-run changes `capturedAt` inside `environment`,
which changes the hash) rather than overwriting a prior directory. Nothing
in this repository automatically writes to `results/` today — this is the
convention `plab-003` (results/provenance/publication pipeline) imports
from and that a maintainer follows manually when publishing a `full` or
`publication`-profile number into a lab's `benchmark.md`.

`results/` itself is gitignored by default (raw JMH JSON / Criterion CSV
are large, regenerable-on-your-own-hardware artifacts per `benchmark.md`'s
existing "raw data and reproduction" convention) — a maintainer commits a
specific `results/<lab-id>/<run-id>/` only when it is the actual evidence
backing a number already written into that lab's `benchmark.md`, exactly as
`design.md`'s CI policy intends ("Publication-grade measurements run on a
designated controlled host and are never synthesized by ordinary shared
runners").
