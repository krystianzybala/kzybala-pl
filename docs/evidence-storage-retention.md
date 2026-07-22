# Evidence storage and retention

## The incident

The 5810 measurement host's `/home` filesystem (295 GB) filled to zero
headroom available to the invoking user. `results/` had grown to
approximately 131 GB after a partial manual cleanup (the repository
checkout itself was ~260 GB including `target/` build output). A running
batch then failed with `No space left on device`.

The dominant cost was never small JSON/CSV/console-log evidence — it was
raw profiler artifacts: `perf.data`, `perf-c2c.data`, unbounded `perf c2c
report --show-all` text dumps (megabytes of irrelevant kernel symbols per
report), and duplicated profiler output across every variant, cursor mode,
batch size, capacity, fork and repetition, with no retention policy at
all. `perf c2c` in particular ran for **every** variant of **every**
c2c-capable lab whenever the host happened to support it
(`LAB_C2C_REQUIRED=1 || perf c2c capability detected` — i.e. essentially
always), regardless of whether that variant's cache-line-ownership
question actually needed it.

Everything in this document exists to make that specific failure mode
structurally impossible: bounded reports, profiler policy gated by lab AND
profile, storage checked before/during/after measurement with hard abort
thresholds, and raw data deleted by default once it has done its job.

## Evidence classes

Every artifact under a results tree is exactly one of five classes
(single source of truth: `scripts/performance-lab/lib/evidence_classify.py`,
imported by both the audit and cleanup tools — never re-derived by
filename pattern in more than one place):

| Class | Examples | Retention |
|---|---|---|
| `canonical` | `environment.json`, `evidence-manifest.json`, `toolchain.json`, `capabilities.json`, `benchmark-profile.json`, `correctness.json`, `run-status.json`, JMH `-rf json` output, `perf-stat*.csv`, `worker-placement*.json`, `placement-policy.json`, the bounded summarized `perf-c2c-report.txt`, `SHA256SUMS`, `batch-manifest.json`, provenance/hash files | **Never deleted** by any tool in this document, under any policy. |
| `diagnostic` | thread dumps (`timeout-jcmd-*.txt`), `timeout-diagnostics.json`, `timeout-process-tree.txt`, bounded `*-console.log`/`*.verify.log`, `rejected.txt` | Kept for failed/partial runs; deletable only under the `keep-canonical` cleanup policy. |
| `raw-profiler` | `perf.data`, `perf-c2c.data`, `*.jfr`, the unbounded pre-summary c2c text dump | Deleted by default once a bounded summary report is generated and validated (successful runs), or immediately on a rejected/timed-out run — unless retention was explicitly requested. |
| `temporary` | `*.tmp`, `*.partial`, in-progress archive temp files | Always safe to delete; never canonical. |
| `build-output` | `target/`, `build/`, `benchmarks.jar` | Not evidence at all; already gitignored. |
| `unclassified` | anything matching none of the above | Surfaced explicitly by the audit report rather than silently bucketed as canonical (which would block cleanup) or discarded (which could destroy real evidence). |

## Profiler policy

Each lab's `scripts/performance-lab/labs/<id>.conf` declares
`LAB_PROFILER_POLICY`:

| Policy | Behavior |
|---|---|
| `none` | No profiler at all — pure JMH numbers only. |
| `stat` | `perf stat` always; `perf c2c` never. Most labs (contention cost, ordering cost, JIT trajectories, cache-topology-derived working sets) are perf-stat/JMH questions and never need cache-line-ownership evidence. |
| `c2c-core-only` | `perf stat` always; `perf c2c` **only** for the lab's declared representative variants (`lab_c2c_variants()`; defaults to every declared variant if the function is absent). |
| `c2c` | `perf stat` always; `perf c2c` for every JMH-kind variant (the old unconditional behavior — used nowhere in this repository's shipped labs; available for a lab that genuinely needs it). |

A lab without an explicit `LAB_PROFILER_POLICY` falls back to `c2c-core-
only` if `LAB_C2C_REQUIRED=1`, else `stat` — never to the old "run c2c
whenever the host supports it" default.

Representative-variant selection (`false-sharing`, `mesi`,
`spsc-ring-buffer`):

- **false-sharing**: both variants (`shared`, `padded`) — the whole lab
  *is* the shared-vs-padded HITM contrast; there is no narrower
  representative subset.
- **mesi**: `writerInvalidation`, `pingPong`, `paddedLines` — the
  ownership-transfer scenarios and the negative-control contrast.
  `singleWriter`/`sharedReaders` never exercise cross-core ownership
  transfer between writers, so c2c adds no signal there.
- **spsc-ring-buffer**: `cached-b1-c1024` only — the defect-revealing
  minimum case (same one the finite-harness matrix split already singles
  out as "core"). Every other cursor-mode/batch/capacity combination and
  every fork/repetition gets full JMH + perf-stat evidence, never its own
  multi-hundred-MB `perf-c2c.data`.

**Profile gates policy further, regardless of the lab's setting**:
`smoke` and `publication-sweep` never run `perf c2c`, full stop. Smoke is
a wiring/termination check, never publication evidence. Sweep exists
specifically so a full parameter matrix does not pay c2c cost per matrix
point — that per-point-c2c pattern is what filled the disk.

## Profiles

| Profile | c2c | perf stat reps | Forks | Publication-eligible |
|---|---|---|---|---|
| `smoke` | never | 1 (lightweight) | 1 | never |
| `development` | per lab policy | 1 | 1 | never |
| `full` | per lab policy | 3 | 2 | only with a clean tree |
| `publication-core` | per lab policy, representative variants only | 3 | 5 | yes, on a controlled host |
| `publication-sweep` | **never**, regardless of lab policy | 1 (lightweight) | 3 | yes, for the metrics it captures (JMH + perf-stat; never c2c) |

`publication` is accepted as a deprecated alias for `publication-core`
(normalized immediately; every downstream artifact records the canonical
name) so existing invocations keep working.

`publication-core` is "full statistical rigor on a representative subset
of profiler-intensive work" — every variant still gets the full JMH/perf-
stat rigor; only the c2c *recording* is restricted to representative
variants. `publication-sweep` is "the full parameter matrix, lightweight
instrumentation" — use it to sweep every cursor mode/batch/capacity/
contender-count combination cheaply, then use `publication-core` (or a
focused `--variant` rerun) for the one or two combinations that need c2c.

Never weaken `publication-core`'s statistical rigor to save disk — the
disk problem is solved by *scoping which variants get raw profiler data*,
never by cutting forks/iterations/repetitions on the numbers actually
published.

## Filesystem preflight and storage monitoring

Both `run-linux-evidence.sh` (per lab-run) and `run-all-benchmarks.sh`
(per batch) check the filesystem containing the output root **before any
build or measurement starts**, and again at every checkpoint listed below.
Availability is read via `os.statvfs`'s `f_bavail` (blocks available to an
**unprivileged** user) — never `total - used`, which would silently count
ext4's root-reserved blocks as if a normal user could use them.

Checkpoints:

- before the batch (`run-all-benchmarks.sh`, against `min_available_before_batch_gib`),
- before every lab (`run-all-benchmarks.sh`, against `abort_threshold_during_batch_gib`),
- before every variant (`run-linux-evidence.sh`),
- after every profiler invocation and after every variant (`run-linux-evidence.sh`),
- before archive generation (both scripts).

Each runner checkpoint appends a structured line to
`<run-dir>/storage-progress.jsonl`:

```json
{"batchBytes": 123456, "variantBytes": 23456, "rawProfilerBytes": 12000,
 "filesystemAvailableBytes": 987654321, "withinBudget": true}
```

Crossing the abort threshold or a total-bytes budget triggers
`mark_storage_abort`: stop the current profiler/child (SIGTERM, SIGKILL
only after the grace period), finalize a partial `run-status.json`
(`failed-storage-preflight` / `failed-storage-budget` /
`failed-artifact-size-limit` — never "benchmark instability"), delete raw
profiler files from the partial run (unless `--retain-failed-raw`), and
exit 4. `run-all-benchmarks.sh` treats exit 4 exactly like the existing
exit-3 timeout convention: the whole batch aborts, the partial run is
preserved under `failed-runs/`, and the batch manifest records
`failed-storage-budget`.

## Raw-profiler cleanup

After a successful `perf c2c record` + bounded-report generation:

1. hash the raw `perf-c2c.data` (SHA-256),
2. record its original byte size,
3. record the summary/report path,
4. delete the raw file **unless** `--retain-raw-profiler-data` was passed,
5. write `<variant>/raw-profiler-retention.json`:

   ```json
   {"rawProfilerFile": "...", "summaryReport": "...",
    "rawProfilerOriginalBytes": 123456789, "rawProfilerSha256": "...",
    "rawProfilerRetained": false}
   ```

The raw file is never deleted before the summary report exists and is
non-empty (`retire_raw_profiler_file` in `lib/storage-lib.sh` refuses
otherwise). A per-variant raw-profiler size cap
(`max_raw_profiler_bytes_per_variant_gib`, default 2 GiB) is enforced
immediately after `perf c2c record` — a pathological recording is rejected
(`failed-artifact-size-limit`) before the summarizer ever runs on it.

Failed and partial runs never preserve raw profiler data by default
either: `mark_rejected` and `mark_timeout` both call
`cleanup_raw_profiler_files_in`, which is skipped only with
`--retain-failed-raw`. What a failed run always keeps: the manifest,
bounded logs, thread dumps, the command line, environment metadata, and
whatever JSON/CSV evidence it managed to produce before failing.

## Bounded text reports

A `perf c2c report --show-all --call-graph none` dump can be megabytes of
irrelevant kernel symbols. `summarize_c2c_report`
(`run-linux-evidence.sh`) filters the raw report down to summary totals,
top cache lines, top symbols and local/remote HITM counts — the raw dump
is written to a `.`-prefixed temp file and deleted immediately after
summarization, never stored. `enforce_text_size_limit` rejects
(`failed-artifact-size-limit`) any generated text report that still
exceeds `max_generated_text_report_size_mib` (default 200 MiB) — a
defensive cap for a pathological report, not the target size (normal
bounded reports are a few KB).

## Results root

The evidence output root is configurable independently of the repository
checkout:

- `--results-root <dir>` / `PERFORMANCE_LAB_RESULTS_ROOT` (both
  `run-linux-evidence.sh` and `run-all-benchmarks.sh`),
- recorded in `environment.json` (`outputRoot`, `outputFilesystem`) and
  the batch manifest (`storage.resultsRoot`, `storage.sameFilesystemAsRepo`),
- if the results root and the repository sit on the same filesystem, that
  is reported explicitly rather than silently assumed safe.

`/tmp` is never assumed to be large enough — no default in this system
points there.

## Storage inventory

```sh
./scripts/performance-lab/audit-evidence-storage.sh results
./scripts/performance-lab/audit-evidence-storage.sh results --json
```

Read-only, deterministic, and does not itself create a large file (the
report is bounded: per-lab/per-variant/per-batch totals plus a top-50-
largest-files list via `heapq.nlargest`, never an unbounded per-file
dump). Reports: total size, size per batch/lab/variant, size by evidence
class, raw-profiler bytes vs canonical bytes, the 50 largest files, and
storage totals grouped by batch state (from each `batch-manifest.json`'s
`state` field, or each standalone run's `run-status.json`).

## Cleanup

```sh
./scripts/performance-lab/cleanup-evidence.sh \
  --results-root results --policy failed-and-partial --dry-run
```

Defaults to `--dry-run`; deletion requires an explicit `--apply`. **Never**
deletes a file classified `canonical`, under any policy (enforced twice:
once by not selecting it, once again defensively at delete time). Never
touches anything outside `--results-root` (source files are never a
candidate).

| Policy | Scope | Deletes |
|---|---|---|
| `raw-profiler-only` | everywhere under the root | `raw-profiler` class only |
| `failed-and-partial` | batches/runs whose state/run-status is not `complete`/`collected` | `raw-profiler` + `temporary` |
| `older-than` (`--older-than-days N`) | everywhere, filtered by file mtime | `raw-profiler` + `temporary` |
| `keep-canonical` | everywhere under the root | `raw-profiler` + `temporary` + `diagnostic` (everything except canonical) |

The report always states: files selected, bytes to recover, batches
affected, and how many canonical files were preserved (encountered and
skipped) — reassurance that the invariant held even on a dry run.

## Archive policy

The default archive (both the per-run archive in `run-linux-evidence.sh`
and the batch archive in `run-all-benchmarks.sh`) excludes
`raw-profiler`-classified files via `tar --exclude` — belt-and-suspenders,
since by default no raw-profiler file should exist by archive time at all
(it was already deleted after its summary was validated). This only
matters when a run was started with `--retain-raw-profiler-data`: the raw
data stays on disk for local inspection but is never bundled into the
archive a maintainer copies off-host as "the" evidence package. Before
building either archive, the estimated input size is checked against
`max_total_batch_size_gib` and the destination filesystem's available
space against `abort_threshold_during_batch_gib`; either check failing
skips archive creation (`failed-artifact-size-limit` /
`failed-storage-budget`) without deleting the evidence already on disk.

## Storage thresholds (defaults, configurable in the host config's `storage:` section)

| Threshold | Default | Config key |
|---|---|---|
| Minimum available before batch | 80 GiB | `min_available_before_batch_gib` |
| Abort threshold during batch | 40 GiB | `abort_threshold_during_batch_gib` |
| Maximum total batch size | 30 GiB | `max_total_batch_size_gib` |
| Maximum raw-profiler bytes per variant | 2 GiB | `max_raw_profiler_bytes_per_variant_gib` |
| Maximum generated text report size | 200 MiB | `max_generated_text_report_size_mib` |
| Smoke profile total budget | 2 GiB | `smoke_budget_gib` |

Every threshold is overridable per-invocation via the matching `PLAB_*`
environment variable (see `scripts/performance-lab/lib/storage-lib.sh`)
for a standalone `run-linux-evidence.sh` invocation outside a batch.

## Related documents

- `docs/linux-evidence-runner.md` — the runner's full preflight/measurement contract.
- `docs/benchmark-artifact-layout.md` — the (separate) local/dev `results/<lab>/<run-id>/` convention for `full`/`publication`-profile numbers hand-published into a lab page.
- `docs/incidents/2026-07-17-spsc-jmh-hang.md` — the prior hard-timeout incident this system's exit-code conventions (3 = timeout, 4 = storage abort) were extended from.
