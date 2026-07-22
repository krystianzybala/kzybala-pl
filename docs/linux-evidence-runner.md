# Native-Linux evidence runner

`scripts/performance-lab/run-linux-evidence.sh` is the only source of
publication measurements and hardware-counter evidence for the Performance
Lab. One common runner, configured per lab by
`scripts/performance-lab/labs/<lab-id>.conf` (the full reference tier:
`false-sharing`, `spsc-ring-buffer`, `cas-contention`, `cache-hierarchy`,
`mesi`, `memory-ordering`, `thread-per-core`, `jit-pipeline`) —
unsupported lab ids are rejected.
The repository-wide environment classification and evidence-state policy
this runner implements is `docs/measurement-environments.md` — native
physical Linux (the dedicated benchmark host) is mandatory for publication
evidence.

## Worker affinity (blocking placement policy)

Process-level `taskset -c A,B` confines the whole JVM but cannot decide
which allowed CPU a specific benchmark worker runs on, and the process's
aggregate `cpu-migrations` counter mixes benchmark workers with compiler/
GC/service threads. Since the first real Precision 5810 run was rejected
on exactly that conflation, placement is now enforced at the worker level:

- each benchmark worker pins **itself** with `sched_setaffinity` (pid 0 =
  calling thread) via the FFM API (`CpuAffinity`/`WorkerPin` in each lab's
  Java project; the Rust harnesses use `libc::sched_setaffinity`), during
  trial setup — never in a measured operation. Pinning failure aborts the
  run; the observed CPU is verified after pinning and again at teardown,
  and the kernel's own per-thread `se.nr_migrations` counter is sampled at
  both points;
- CPU assignment comes from immutable JVM properties the runner sets
  (`-Dplab.cpuA/-Dplab.cpuB` for two-role labs, `-Dplab.workerCpus=<csv>`
  for N contenders — worker *i* takes the i-th entry, a deterministic
  prefix of the validated `--cpus` list);
- every fork writes `worker-placement-<pid>-<role>.json`; the runner
  merges them into `<variant>/worker-placement.json` and **rejects the
  run** (`run-status.json: rejected`, diagnostic only, never imported) if
  any worker was unpinned, off its intended CPU, migrated during the
  trial, or shared a CPU with another worker of the same fork;
- the aggregate process `cpu-migrations` (from `jmh-placement.csv`) is
  retained as host/JVM-noise evidence in `placement-policy.json` with its
  own bound (50/s of task-clock); it is blocking **only** when worker
  placement is unavailable — auxiliary JVM-thread migrations are never
  classified as worker migrations, and the counter is never discarded;
- `taskset` remains as secondary containment only.

### Rust worker protocol (post 2026-07-18 affinity-diagnostics fix)

The first real focused SPSC run exposed a diagnostics defect in the Rust
persistent-worker harness: the parent called the end-of-run capture again
after `join()`, overwriting both workers' end state with the **main
thread's** CPU and migration counter — producing identical migration
counts for producer and consumer and a false "consumer finished on CPU 2"
violation while the actual pinning was correct. The harness now enforces:

- each worker, **on its own thread**: reads its own Linux TID
  (`syscall(SYS_gettid)` — never the process PID), samples its migration
  counter *before* pinning, pins itself (`sched_setaffinity(0)` = calling
  task), verifies the mask via `sched_getaffinity` (**exactly** the
  intended CPU — the two-CPU `taskset` containment mask never counts as
  pinned), verifies `sched_getcpu()`, samples migrations again, then
  reports READY; the parent releases the start barrier only after both
  workers proved their placement (duplicate or invalid TIDs abort before
  any timing);
- `pinned` requires all of: syscall success + mask exactly `{intended}` +
  observed CPU == intended + valid own TID — `affinitySetSucceeded`,
  `affinityMaskVerified`, `cpuAfterPinVerified` are reported separately;
- migration accounting is windowed: `migrationsBeforePin`,
  `migrationsAfterPin`, `migrationsAtMeasurementStart`,
  `migrationsAtMeasurementEnd` are all reported;
  `migrationsDuringMeasurement = atMeasurementEnd − atMeasurementStart`
  is the publication gate (must be 0, smoke included — duration may
  shrink, placement rules never do). Startup/pre-pin migrations are
  reported but never counted; a *decreasing* counter is an infrastructure
  error;
- every end-of-run observation (CPU, mask as an explicit `allowedCpus`
  list, migrations, capturing TID) is taken by the worker itself and
  returned through the join handle; `capturedByTid` must equal the
  worker's own TID and the per-worker procfs paths
  (`/proc/self/task/<tid>/sched`) must be distinct — the parent never
  queries worker placement;
- naming: per-worker counters (`producerWorkerMigrations`-class evidence)
  are never comparable with the surrounding `perf stat` process-tree
  counter (`processTreeCpuMigrations` — launcher, service and compiler
  threads included). Java worker placement is never rejected on a
  non-zero process-tree count when per-worker evidence proves zero
  migrations.

JVM profile for evidence runs: `-Xms1g -Xmx1g -XX:+UseSerialGC
-XX:ActiveProcessorCount=<pinned cpu count>
--enable-native-access=ALL-UNNAMED`. SerialGC is chosen because the
measured paths allocate nothing and it minimizes auxiliary GC worker
threads (less process-level migration noise); heap safety is demonstrated
by the correctness gate running under the same fixed heap, and the
resolved `CICompilerCount`/`ActiveProcessorCount` are captured in
toolchain.json — the GC was not changed to prettify results, and worker
placement (not GC choice) is what gates publication.

## Editorial policy

- **macOS workstation** — development, correctness testing and benchmark
  *smoke* validation only. Its numbers are never publication evidence for
  HITM, cache-to-cache transfers or per-cache-line contention.
- **Native, controlled Linux host** — publication measurements and
  hardware-counter evidence, collected exclusively by this runner.
- **Emulation, containers on foreign architectures, shared CI runners,
  synthetic examples** — never publication evidence. The runner refuses to
  run when virtualization is detected; CI never fabricates a live `perf`
  execution (`scripts/test-linux-evidence.js` uses fixtures and stubs for
  command construction and failure paths only).
- Synthetic `perf` output may appear **only** in a clearly labeled
  interpretation exercise (exercise 3 of the lab). It must never appear in
  measured results, evidence, conclusions, regression baselines,
  Java-versus-Rust comparisons, or verified-maturity calculations.
- Until native Linux artifacts are imported, the lab's evidence status is
  rendered **`awaiting-native-linux-measurement`** — not "unavailable".

## Usage (on the dedicated Linux host)

Run everything as the NORMAL user (never sudo — the runner refuses root).
Select CPU ids from `lscpu -e=CPU,CORE,SOCKET,NODE,ONLINE`: distinct
physical cores, no SMT siblings, same socket/NUMA node by default.

```sh
# preflight first on a new host (validates everything, measures nothing):
./scripts/performance-lab/run-linux-evidence.sh false-sharing \
  --profile publication-core --cpus <CPU_A>,<CPU_B> --preflight-only

# per-lab publication runs (representative variants, full rigor):
./scripts/performance-lab/run-linux-evidence.sh false-sharing \
  --profile publication-core --cpus <CPU_A>,<CPU_B>
./scripts/performance-lab/run-linux-evidence.sh spsc-ring-buffer \
  --profile publication-core --cpus <CPU_A>,<CPU_B>
./scripts/performance-lab/run-linux-evidence.sh cas-contention \
  --profile publication-core --cpus <CPU0>,<CPU1>,<CPU2>,<CPU3>,<CPU4>,<CPU5>,<CPU6>,<CPU7>

# full parameter matrix, lightweight instrumentation (no c2c per point):
./scripts/performance-lab/run-linux-evidence.sh spsc-ring-buffer \
  --profile publication-sweep --cpus <CPU_A>,<CPU_B>

# or the whole reference tier in one batch (fill the cpu_sets in the host
# config from lscpu first):
./scripts/performance-lab/run-all-benchmarks.sh \
  --profile publication-core \
  --host-config config/benchmark-hosts/precision-5810.yaml \
  --repetitions 2
```

`--profile publication` is accepted as a deprecated alias for
`publication-core`. Evidence-storage discipline (filesystem preflight,
per-variant/per-lab storage checks, profiler-policy-gated `perf c2c`,
raw-profiler cleanup, bounded reports): `docs/evidence-storage-retention.md`.

`--cpus` is required — publication runs never silently choose CPUs. Pick
logical CPU ids from `lscpu -e` (two distinct physical cores for the
two-worker labs; CAS derives its 1/2/4/8-contender scenarios from however
many validated CPUs you pass, and per-scenario subsets are deterministic
prefixes recorded in benchmark-profile.json). Every pair in the set is
validated against `lscpu -e=CPU,CORE,SOCKET,NODE,ONLINE`:

- both CPUs must exist and be online,
- they must be different logical CPUs,
- they must map to **different physical cores** (SMT siblings are rejected
  — siblings share an L1 and cannot exhibit cross-core false sharing),
- same socket and same NUMA node by default; `--allow-cross-socket` runs
  the explicitly separate `cross-socket` scenario.

Other flags: `--profile publication-core|publication-sweep|full|development|smoke`,
`--out <dir>` / `--results-root <dir>` (equivalent; also settable via
`PERFORMANCE_LAB_RESULTS_ROOT`), `--preflight-only`, `--allow-virtualized`,
`--dry-run` (prints every planned command, creates the metadata skeleton,
executes no measurement), `--skip-load-check` (non-publication runs only),
`--retain-raw-profiler-data` / `--retain-failed-raw` (see
`docs/evidence-storage-retention.md` — by default, raw `perf.data`/
`perf-c2c.data` is deleted once its bounded summary report is validated,
or immediately on a rejected/timed-out run).

### Virtualization detection (exit status, never stdout text)

Physical hosts are **accepted**: `systemd-detect-virt` exiting `1` —
typically while printing the literal `none` — means no virtualization. The
string `none` is never classified as virtualization. Detection
(`detect_virtualization`, `lib/evidence-lib.sh`) queries `--vm --quiet` and
`--container --quiet` separately and uses their exit codes as the source of
truth; the type labels (`kvm`, `vmware`, `docker`, `podman`, …) are only
read after a positive exit status. The result is recorded in
`capabilities.json` as:

```json
"virtualization": { "detected": false, "vmType": null, "containerType": null,
                    "environmentKind": "physical", "publicationEligible": true }
```

### `--preflight-only`

Runs **every** preflight check — OS, virtualization/container detection,
CPU topology (existence, online status, physical-core distinction,
SMT-sibling rejection, socket/NUMA policy), host load, required commands,
`perf stat` and `perf c2c` capability, Java/Maven toolchain, repository
revision and dirty state, the full benchmark correctness suite, and
output-directory permissions — then stops. It executes no JMH measurement,
no full `perf stat` runs, no `perf c2c record`, creates no evidence
archive, and never claims native performance validation succeeded. On
success it prints `Preflight passed.` with the host type, publication
eligibility and selected CPUs. Run it once on a new host before the first
publication run.

### `--allow-virtualized` (smoke only)

Permits command-construction and wiring validation on a VM or container
**only** with `--profile smoke` or `--profile development`; combined with
`publication` or `full` it fails. Such runs always record
`"publicationEligible": false, "environmentKind": "virtualized"`. There is
no option that allows virtualized publication evidence.

### `--variant <name>` (focused rerun)

Runs exactly one variant from the lab's matrix — the cheap way to re-verify
a defect-revealing case (e.g. the SPSC hang case `cached-b1-c1024`) or to
smoke a fix without paying for the full matrix. The name is validated
against the lab configuration; unknown names fail with the available list.
The manifest records `"variantSelection": "focused:<name>"` — a focused run
never masquerades as the full matrix, and full-matrix evidence claims still
require a run with `"variantSelection": "all"`.

### `--component rust-harness` (focused Rust-only rerun)

Runs **only** the Rust persistent-worker harness for the selected
variant(s) — no Maven correctness gate, no JMH, no `perf stat`/`perf c2c`
around JMH, no aux harness — the fast loop for diagnosing worker-affinity
issues:

```bash
./scripts/performance-lab/run-linux-evidence.sh \
  spsc-ring-buffer \
  --profile smoke \
  --cpus <CPU_A>,<CPU_B> \
  --variant cached-b1-c1024 \
  --component rust-harness \
  --out /tmp/spsc-rust-affinity-smoke
```

It finishes in seconds (plus a one-time `cargo` build) and exits non-zero
on any sequence failure, wrong affinity mask, wrong observed CPU,
migration during measurement, duplicate worker TID (classified
`rejected: rust-harness-violation`), or hang (hard timeout →
`failed-benchmark-timeout`, exit 3). `componentSelection` is recorded in
the manifest; component runs — like smoke/development profiles — are
never publication-eligible.

### Hard wall-clock timeouts (`failed-benchmark-timeout`)

Since the batch-20260717T150131Z SPSC hang (a JMH fork spun 14h40m at 100%
CPU with no bound anywhere — see
`docs/incidents/2026-07-17-spsc-jmh-hang.md`), every external measurement
invocation (JMH run, each `perf stat` repetition, `perf c2c`, aux/Rust
harnesses) executes under a hard wall-clock budget derived from the
profile: publication 1800s, full 900s, development 420s, smoke 180s per
invocation — generous multiples of the expected duration, so a fired
timeout always means a genuine hang. On expiry the runner:

1. captures diagnostics from the **still-live** tree first: a process-tree
   snapshot, `jcmd <pid> Thread.print / VM.command_line / VM.flags` and
   `taskset -pc <pid>` for every surviving JVM
   (`timeout-diagnostics.json`, `timeout-*.txt` in the variant directory);
2. terminates the whole process tree with SIGTERM; SIGKILL is only the
   last resort after a 30s grace period, never the normal path;
3. stamps `run-status.json` with `"runStatus": "failed-benchmark-timeout"`
   (diagnostic-only, never canonical evidence) and exits with code 3.

The batch orchestrator treats exit 3 as `failed-benchmark-timeout`,
quarantines the run under `failed-runs/`, writes the batch manifest with
that state and **aborts the entire batch** — a host that just carried a
hung 100%-CPU JVM must not keep measuring, and a hang needs human
attention. Timed-out runs are never silently retried or skipped.

Both the runner and the orchestrator install EXIT/INT/TERM traps that
forward SIGTERM to the running benchmark tree, so an interrupted run never
leaves benchmark JVMs behind.

## What must hold before measurement starts (all enforced)

`perf` installed and usable (`perf stat` can count; `perf c2c record -e
list` can open its memory events — not every x86_64 part supports useful
c2c evidence), no virtualization/emulation detected, valid CPU selection,
identifiable git revision (a dirty tree is recorded with a diff hash),
1-minute load per core ≤ 0.20, writable output directory, and a **passing
correctness gate** (`mvn test`: fixture-based counter correctness, JOL
layout verification, benchmark structural contract). Capability results are
stored in `capabilities.json`; unsupported fields are recorded as
`unavailable`, never omitted.

## Collection design

Variants are never mixed in one invocation. Per variant (`shared`,
`padded`), each in its own output directory with its own provenance:

1. **JMH evidence run** — `taskset -c A,B java -Xms1g -Xmx1g
   -XX:+UseParallelGC -jar benchmarks.jar
   'FalseSharingLinuxEvidenceBenchmark' -p layout=<variant> -t 2 -f 5 -wi 5
   -w 1s -i 10 -r 1s -rf json` (publication profile; 5 independent forks —
   one long invocation is not a substitute). Wrapped in a counting-mode
   `perf stat -e cpu-migrations,context-switches,task-clock` whose output
   enforces the placement policy (max 5 cpu-migrations/second of
   task-clock; a violating run fails).
2. **perf stat** — 3 independent repetitions, `-x,` CSV, events: cycles,
   instructions, cache-references, cache-misses, branches, branch-misses,
   task-clock, context-switches, cpu-migrations, page-faults. Each
   repetition wraps a single-fork JMH run whose JSON is kept next to the
   CSV so per-operation ratios divide counters by operations from the same
   execution. `<not supported>` rows are recorded, never discarded.
3. **perf c2c** — `perf c2c record -o perf-c2c.data -- taskset ...` then
   `perf c2c report --stdio --show-all --call-graph none`. The binary
   `perf-c2c.data` is preserved and hashed; the text report is not a
   substitute for it. JVM source-line symbolization is not required for
   HITM evidence; symbolization capability is recorded separately in
   `capabilities.json`.

`taskset` confines every JVM thread (including GC/JIT) to the two selected
CPUs; it cannot pin writer A to CPU A specifically — migrations between
the two allowed CPUs are therefore counted and bounded by policy instead.

## Artifact layout

```
results/false-sharing/<run-id>/
├── environment.json        # governor, freq, turbo, SMT, NUMA, microcode,
│                           # perf restrictions, virt detection, load, commit
├── topology.txt            # raw lscpu, lscpu -e, uname -a, cpuinfo, cmdline
├── capabilities.json
├── toolchain.json          # java/maven/JMH versions, full JVM args
├── benchmark-profile.json  # the exact resolved profile
├── correctness.json (+ correctness-console.log)
├── shared/   jmh.json, jmh-placement.csv, perf-stat.csv (+ -r2/-r3 and
│             matched perf-stat-jmh*.json), perf-c2c.data,
│             perf-c2c-report.txt, canonical-jmh.json*,
│             canonical-perf-stat.json*
├── padded/   (same shape)
├── comparison.json*
├── evidence-manifest.json
└── SHA256SUMS
```

Entries marked `*` are produced at import time on the repository machine
(the measurement host is not assumed to have node). Every artifact is
SHA-256 hashed; the manifest references source commit, dirty-tree state +
diff hash, environment, correctness, profile, raw artifacts and (after
import) canonical artifacts and the derived comparison.

## After the run

The runner prints and produces one archive, e.g.
`false-sharing-<run-id>-linux-evidence.tar.zst`. On the repository machine:

```sh
./scripts/performance-lab/verify-evidence.sh false-sharing-<run-id>-linux-evidence.tar.zst
./scripts/performance-lab/import-evidence.sh false-sharing-<run-id>-linux-evidence.tar.zst
```

`verify` checks every hash and manifest reference. `import` copies the run
into `results/false-sharing/<run-id>/` (immutable — an existing directory
is never overwritten), runs the plab-003 importers (JMH + perf-counter) to
produce schema-validated canonical records, and generates
`comparison.json`: throughputRatio, cyclesPerOperation,
instructionsPerOperation, cacheMissesPerMillionOperations,
hitmPerMillionOperations — raw values, formulas and input references kept;
display rounding separate.

## What import does NOT do

- It does not mark the lab, any record, or any importer "verified" or
  "live-publication-validated". `evidence-maturity.js` derives maturity
  from evidence dimensions; a fresh import starts at reproduction 0/1,
  no reviewer — i.e. *draft*.
- Promoting the perf importer's capability in
  `capability-registry.js` happens only after a maintainer reviews real
  artifacts produced by this runner on real hardware.
- Throughput alone never establishes causation. The false-sharing
  conclusion requires, together: shared-vs-padded JMH results, `perf c2c`
  HITM evidence, equivalent correctness results, and stable
  placement/environment metadata.

## Review checklist (before any content claims "verified")

1. `verify-evidence.sh` passes; run directory hashes intact.
2. `environment.json`: no virtualization; governor/turbo state acceptable;
   selected CPUs' validation line shows two physical cores, same
   socket/node (or the run is explicitly the cross-socket scenario).
3. `correctness.json` status `passed`; console log clean.
4. Placement: `jmh-placement.csv` migration counts within policy.
5. JMH: shared vs padded distributions coherent across all 5 forks (no
   bimodality suggesting placement drift).
6. `perf stat`: cache-miss gap consistent across all 3 repetitions;
   `<not supported>` rows acknowledged.
7. `perf c2c` report: hottest line's HITM concentrated on two offsets in
   the shared run, collapsed in the padded run; raw `perf-c2c.data`
   retained.
8. Only then: update the lab content from the canonical records, record
   reproduction/reviewer fields in a documented follow-up, and consider a
   maintainer change to `capability-registry.js` with the justification
   written into `docs/benchmark-results-importers.md`.
