# Incident: evidence-storage exhaustion (5810 `/home` filesystem)

**Status:** resolved (profiler-policy gating, storage preflight/monitoring, raw-profiler retention and cleanup tooling landed; regression-tested)
**Affected host:** the canonical measurement host (dedicated Dell Precision 5810)
**Affected filesystem:** `/home` (ext4, 295 GB)

## What happened

```text
/home/krystian/private/kzybala-pl: ~260 GB
results/: ~131 GB (after a partial manual cleanup)
/dev/sda2 ext4: size 295 GB, used 280 GB, available to the regular user: 0
```

A running publication batch failed with `No space left on device`. The
filesystem had reached zero headroom available to the invoking (non-root)
user.

## Root cause

`perf c2c` ran for **every variant** of **every** c2c-capable lab whenever
the host happened to support it:

```sh
if [ "${LAB_C2C_REQUIRED:-0}" = "1" ] || [ "$PERF_C2C_OK" = "true" ]; then
  # perf c2c record + report, unconditionally, every variant
fi
```

— effectively always, since `PERF_C2C_OK` reflects host capability, not
whether that specific variant's question actually needed cache-line-
ownership evidence. Three multiplicative factors compounded this: every
variant of a matrix lab (cursor mode × batch size × capacity for SPSC, five
scenarios for MESI), every fork/repetition, and no retention policy on the
resulting artifacts at all. Contributing factors:

- **Raw profiler data was permanent.** `perf.data`/`perf-c2c.data` were
  never deleted after use — every recording accumulated forever.
- **`perf c2c report` was unbounded.** `--show-all --call-graph none`
  produces megabytes of irrelevant kernel-symbol text per report, stored
  verbatim.
- **No filesystem preflight.** Nothing checked available space before a
  build or measurement started, so a batch could begin on an
  already-nearly-full disk.
- **No storage monitoring during the batch.** Nothing checked storage
  between labs, variants or profiler invocations, so a batch ran to
  completion (or to `No space left on device`) regardless of how much disk
  it had already consumed.
- **Failed and partial runs kept everything.** A rejected or timed-out run
  preserved its raw profiler data exactly like a successful one.

## Fix

See `docs/evidence-storage-retention.md` for the full system. Summary:

- **Profiler policy per lab** (`LAB_PROFILER_POLICY`: `none`/`stat`/
  `c2c-core-only`/`c2c`), with `c2c-core-only` restricted to each lab's
  declared representative variants (`lab_c2c_variants()`). Applied to
  false-sharing (both variants — the whole lab is the contrast), MESI
  (`writerInvalidation`, `pingPong`, `paddedLines` — not `singleWriter`/
  `sharedReaders`, which never exercise ownership transfer), and
  spsc-ring-buffer (`cached-b1-c1024` only — the defect-revealing minimum
  case, not every cursor-mode/batch/capacity/fork/repetition). Every other
  shipped lab is `stat`-only: none of them need cache-line-ownership
  evidence at all.
- **Profile-level gating on top of lab policy**: `smoke` and
  `publication-sweep` never run `perf c2c`, regardless of what the lab
  wants. A new `publication-sweep` profile exists specifically so the full
  parameter matrix can be swept cheaply (JMH + lightweight perf stat, no
  c2c per point); `publication-core` (renamed from `publication`, which is
  kept as a deprecated alias) is full rigor on the representative subset.
- **Raw-profiler cleanup by default**: after a successful, bounded,
  validated summary report, the raw `perf-c2c.data` is hashed, sized and
  deleted (`--retain-raw-profiler-data` opts out). Rejected and timed-out
  runs delete their raw profiler data by default too
  (`--retain-failed-raw` opts out).
- **Bounded c2c reports**: summarized to totals/top-cache-lines/top-
  symbols/HITM counts, never the raw `--show-all` dump; a hard size cap
  (`failed-artifact-size-limit`) catches any pathological report.
- **Filesystem preflight** before any build/measurement, plus storage
  checks before/after every variant, before every lab, and before archive
  generation — all configurable in the host config's `storage:` section,
  all aborting with their own reason code (`failed-storage-preflight`/
  `failed-storage-budget`/`failed-artifact-size-limit`), never
  misclassified as benchmark instability.
- **Audit and cleanup tooling**:
  `scripts/performance-lab/audit-evidence-storage.sh` (read-only inventory
  by evidence class/lab/variant/batch state) and
  `scripts/performance-lab/cleanup-evidence.sh` (`--dry-run` by default,
  `failed-and-partial`/`raw-profiler-only`/`older-than`/`keep-canonical`
  policies, never deletes canonical evidence).

## A real bug caught by the new tests

While testing the representative-variant restriction, a `set -e` hazard
surfaced: `is_c2c_representative_variant "$variant" && RUN_C2C=1` inside a
`case` arm propagated the check's own exit status (1, for every
non-representative variant) as the enclosing function's return status.
Since the function was called as a bare statement (not inside `if`/`&&`),
`errexit` treated that as a command failure and aborted the entire script
— meaning a real MESI or SPSC `publication-core` run would have crashed
outright on its first non-representative variant. Fixed by using
`if is_c2c_representative_variant "$variant"; then RUN_C2C=1; fi`, whose
own exit status is always 0 regardless of the condition's outcome.
False-sharing's dry-run test never caught this because both of its
variants are representative (the check's exit status was always 0 there,
never exercising the failure path) — the fix was found once mesi's
non-representative `singleWriter`/`sharedReaders` variants were exercised
in `scripts/test-evidence-storage.js`.

## Immediate real-host validation (not yet performed)

Per the incident-response workflow, the next real-host steps are, in
order: run `audit-evidence-storage.sh` against the actual `results/`
directory to get the real inventory; run `cleanup-evidence.sh
--policy failed-and-partial --dry-run` and review the byte-recovery
estimate before applying it; run a single focused `spsc-ring-buffer`
`--profile smoke --variant cached-b1-c1024` smoke check and confirm no
`perf-c2c.data`, no giant text report, total output under 2 GiB; then one
publication-core SPSC variant and confirm raw-profiler cleanup and total
storage bounds. The full eight-lab publication batch is deliberately not
run again until both of those checks pass.
