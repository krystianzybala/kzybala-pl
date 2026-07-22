#!/usr/bin/env bash
# Native-Linux publication-evidence runner for the Performance Lab
# (docs/linux-evidence-runner.md) — one runner, per-lab configuration.
#
#   ./scripts/performance-lab/run-linux-evidence.sh \
#     <lab-id> --profile publication-core --cpus <CPU_A>,<CPU_B>
#
# Run as the NORMAL user (never sudo); pick CPU ids from
# lscpu -e=CPU,CORE,SOCKET,NODE,ONLINE — distinct physical cores, no SMT
# siblings, same socket/NUMA node by default.
#
# Options: --profile publication-core|publication-sweep|full|development|
# smoke ("publication" is accepted as a deprecated alias for
# publication-core), --cpus A,B (required), --allow-cross-socket,
# --preflight-only (run every preflight check, then stop before any
# measurement), --allow-virtualized (smoke|development wiring checks only
# — never publication-core|publication-sweep|full), --out <dir> /
# --results-root <dir> (equivalent — where evidence is written; also
# settable via the PERFORMANCE_LAB_RESULTS_ROOT environment variable),
# --dry-run, --skip-load-check, --variant <name> (run ONE variant only —
# focused smoke/diagnosis; recorded as a focused run in the manifest,
# never presented as the full variant matrix), --component all|rust-harness
# (rust-harness runs ONLY the Rust persistent-worker harness — no Maven
# gate, no JMH, no perf stat/c2c around JMH — for fast affinity diagnosis;
# the harness itself exits non-zero on any sequence, affinity-mask,
# placement, migration, or TID-integrity violation), --retain-raw-profiler-
# data (keep perf.data/perf-c2c.data/JFR after a successful summary report
# instead of the default delete-after-validated-summary policy),
# --retain-failed-raw (keep raw profiler recordings from a rejected/
# timed-out run instead of the default delete-on-failure policy).
#
# Smoke/development profiles and focused --variant/--component runs are
# never publication-eligible, whatever the host.
#
# Every external measurement invocation runs under a hard wall-clock
# timeout (run_with_deadline): on expiry, jcmd/thread-dump diagnostics are
# captured from the still-alive JVMs, the whole process tree is terminated
# with SIGTERM (SIGKILL only as a last resort after the grace period), the
# run is stamped failed-benchmark-timeout, and the runner exits 3 so batch
# orchestration aborts instead of silently continuing (regression guard for
# the batch-20260717T150131Z SPSC producer hang, which ran 14h40m).
#
# Storage discipline (docs/evidence-storage-retention.md, post the
# 2026-07 /home-filesystem-exhaustion incident — 131 GB of results/, mostly
# raw perf.data/perf-c2c.data and unbounded c2c text reports, filled a
# 295 GB disk to zero headroom): a filesystem preflight runs before any
# build/measurement, storage is checked before/after every variant and
# profiler invocation, perf c2c is gated by each lab's declared
# PROFILER_POLICY (none|stat|c2c-core-only|c2c) and — for c2c-core-only —
# restricted to the lab's declared representative variants
# (lab_c2c_variants), c2c text reports are summarized and bounded (never
# the raw --show-all --call-graph dump), and raw profiler recordings are
# deleted by default once their bounded summary report is generated and
# validated (or on any rejected/timed-out run) unless retention was
# explicitly requested. A crossed abort threshold stops the current
# profiler, terminates children, finalizes a partial diagnostic manifest,
# and aborts this run — never silently continuing, never misclassified as
# benchmark instability (failed-storage-preflight / failed-storage-budget
# / failed-artifact-size-limit are their own run-status reasons).
#
# Physical hosts are accepted: systemd-detect-virt exiting 1 (typically
# printing the literal "none") means no virtualization — detection is by
# exit status, never by stdout text (detect_virtualization in
# lib/evidence-lib.sh).
#
# Collects, per variant (shared / padded, each in its own JVM invocation,
# its own perf session and its own output directory):
#   - JMH -rf json evidence (multi-fork publication profile),
#   - perf stat counters (profile-dependent repetitions, CSV),
#   - perf c2c recording, ONLY where the lab's profiler policy and profile
#     select it for this variant — a bounded --show-all-derived summary
#     report is always what is kept; the binary perf-c2c.data is deleted
#     by default once that summary is validated,
# plus host topology, environment, toolchain, capability results,
# correctness-gate results, the resolved profile, storage/filesystem
# provenance, a SHA-256 manifest over every artifact, and a single archive
# (raw-profiler-excluded by default) to copy off the host.
#
# Policy (docs/linux-evidence-runner.md): this runner is the ONLY source of
# publication hardware-counter evidence for this lab. macOS runs are
# development/correctness/smoke only. Emulated or virtualized environments,
# shared CI runners and synthetic outputs are never publication evidence.
# Running this script does not promote anything to "verified" — the
# artifacts must be imported (import-evidence.sh) and human-reviewed.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck source=lib/evidence-lib.sh
source "${SCRIPT_DIR}/lib/evidence-lib.sh"
# shellcheck source=lib/storage-lib.sh
source "${SCRIPT_DIR}/lib/storage-lib.sh"

# --- Arguments ---------------------------------------------------------------
LAB_ID="${1:-}"; shift || true
PROFILE="publication-core"
CPUS=""
ALLOW_CROSS_SOCKET=0
ALLOW_VIRTUALIZED=0
PREFLIGHT_ONLY=0
DRY_RUN=0
SKIP_LOAD_CHECK=0
SELECTED_VARIANT=""
COMPONENT="all"
OUT_ROOT="${PERFORMANCE_LAB_RESULTS_ROOT:-${REPO_ROOT}/results}"
RETAIN_RAW_PROFILER_DATA=0
RETAIN_FAILED_RAW=0

while [ $# -gt 0 ]; do
  case "$1" in
    --profile) PROFILE="$2"; shift 2 ;;
    --cpus) CPUS="$2"; shift 2 ;;
    --variant) SELECTED_VARIANT="$2"; shift 2 ;;
    --component) COMPONENT="$2"; shift 2 ;;
    --allow-cross-socket) ALLOW_CROSS_SOCKET=1; shift ;;
    --allow-virtualized) ALLOW_VIRTUALIZED=1; shift ;;
    --preflight-only) PREFLIGHT_ONLY=1; shift ;;
    --out|--results-root) OUT_ROOT="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --skip-load-check) SKIP_LOAD_CHECK=1; shift ;;
    --retain-raw-profiler-data) RETAIN_RAW_PROFILER_DATA=1; shift ;;
    --retain-failed-raw) RETAIN_FAILED_RAW=1; shift ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

fail() { echo "run-linux-evidence: $*" >&2; exit 1; }

# --- Privilege model -----------------------------------------------------------
# The runner is executed as the NORMAL user: Java, Maven, Cargo, JMH,
# Criterion and every harness run as the invoking user, and all artifacts
# are owned by the invoking user. Whole-script sudo is rejected — perf's
# privileged requirements are satisfied once, out of band, by the
# documented host setup (kernel.perf_event_paranoid=-1), after which perf
# itself also runs unprivileged. No measurement subprocess is ever wrapped
# in sudo (a sudo-wrapped `perf stat -- java` would run the JVM as root).
if [ "$(id -u)" = "0" ]; then
  fail "do not run this script with sudo/as root — benchmarks and builds must run as the invoking user. One-time host setup for unprivileged perf (run separately): sudo sysctl kernel.perf_event_paranoid=-1 kernel.kptr_restrict=0"
fi

# --- Lab configuration ---------------------------------------------------------
# One common runner, per-lab configuration (never divergent script copies).
# A lab without a config file is rejected. Test hook (never needed in real
# use, mirrors run-all-benchmarks.sh's PLAB_CONF_DIR): a synthetic labs
# directory so storage/retention tests can exercise a full non-dry-run
# measurement loop against fixture stubs without touching real lab code.
LABS_DIR="${PLAB_LABS_DIR:-${SCRIPT_DIR}/labs}"
LAB_CONF="${LABS_DIR}/${LAB_ID:-none}.conf"
if [ -z "$LAB_ID" ] || [ ! -f "$LAB_CONF" ]; then
  SUPPORTED="$(ls "${LABS_DIR}" 2>/dev/null | sed 's/\.conf$//' | tr '\n' ' ')"
  fail "unsupported lab id '${LAB_ID:-<none>}' — supported: ${SUPPORTED:-none}"
fi
# shellcheck source=/dev/null
source "$LAB_CONF"

# --variant restricts the run to ONE variant from the lab's matrix —
# focused smoke/diagnosis after a failure, without paying for the full
# matrix. The selection is validated against the config and recorded in the
# manifest; a focused run never masquerades as the full matrix.
if [ -n "$SELECTED_VARIANT" ]; then
  case " $(lab_variants) " in
    *" ${SELECTED_VARIANT} "*) : ;;
    *) fail "unknown variant '${SELECTED_VARIANT}' for lab ${LAB_ID} — available: $(lab_variants)" ;;
  esac
fi
effective_variants() {
  if [ -n "$SELECTED_VARIANT" ]; then echo "$SELECTED_VARIANT"; else lab_variants; fi
}

# --- Profiler policy resolution (docs/evidence-storage-retention.md) ---------
# Each lab declares LAB_PROFILER_POLICY (none|stat|c2c-core-only|c2c) and,
# for c2c-core-only, an optional lab_c2c_variants() function naming the
# representative variants that actually get c2c evidence — cache-line
# ownership/HITM evidence is central to false-sharing/mesi/spsc, but that
# does not mean every cursor mode/batch size/capacity/fork/repetition
# needs its own multi-hundred-MB perf-c2c.data. A lab without an explicit
# policy falls back to the pre-existing LAB_C2C_REQUIRED signal (never
# silently promoted to unconditional c2c the way the old capability-gated
# behavior did).
LAB_PROFILER_POLICY="${LAB_PROFILER_POLICY:-$([ "${LAB_C2C_REQUIRED:-0}" = "1" ] && echo "c2c-core-only" || echo "stat")}"
case "$LAB_PROFILER_POLICY" in
  none|stat|c2c-core-only|c2c) : ;;
  *) fail "lab ${LAB_ID}: invalid LAB_PROFILER_POLICY '${LAB_PROFILER_POLICY}' (none|stat|c2c-core-only|c2c)" ;;
esac
variant_profiler_policy() {
  if declare -f lab_profiler_policy >/dev/null 2>&1; then
    lab_profiler_policy "$1"
  else
    echo "$LAB_PROFILER_POLICY"
  fi
}
c2c_representative_variants() {
  if declare -f lab_c2c_variants >/dev/null 2>&1; then
    lab_c2c_variants
  else
    lab_variants
  fi
}
is_c2c_representative_variant() {
  case " $(c2c_representative_variants) " in
    *" $1 "*) return 0 ;;
    *) return 1 ;;
  esac
}
# resolve_profiler_action <variant> — sets RUN_STAT / RUN_C2C to 0|1.
# smoke and publication-sweep NEVER run c2c, regardless of lab policy:
# smoke is a wiring/termination check (never publication evidence) and
# sweep exists precisely so a full parameter matrix does not pay c2c cost
# per point (that per-point-c2c pattern is what filled the disk).
resolve_profiler_action() {
  local variant="$1" policy
  policy="$(variant_profiler_policy "$variant")"
  RUN_STAT=1
  RUN_C2C=0
  [ "$policy" = "none" ] && RUN_STAT=0
  case "$PROFILE" in
    smoke|publication-sweep) RUN_C2C=0 ;;
    *)
      case "$policy" in
        c2c) RUN_C2C=1 ;;
        # `&&` here would propagate is_c2c_representative_variant's exit
        # status (1 for every non-representative variant) as this whole
        # function's own return status — under `set -e`, a non-representative
        # variant would then abort the entire script. `if` never does that.
        c2c-core-only) if is_c2c_representative_variant "$variant"; then RUN_C2C=1; fi ;;
      esac
      ;;
  esac
}

# --component rust-harness availability check (needs the parsed CPU list —
# lab_rust_evidence_cmd interpolates the validated CPUs into the command).
case "$COMPONENT" in
  all|rust-harness) : ;;
  *) fail "unknown --component '${COMPONENT}' (all|rust-harness)" ;;
esac
[ -n "$CPUS" ] || fail "--cpus is required: publication runs never silently choose CPUs"
IFS=',' read -r -a CPU_LIST <<<"$CPUS"
CPU_COUNT="${#CPU_LIST[@]}"
# Cardinality is EXACT per lab (single=1, pair=2, quad=4, octet=8): too few
# CPUs cannot host the workers, and extra CPUs would silently change the
# placement scenario — both are cardinality errors, never adjusted.
LAB_CPUS_EXACT="${LAB_CPUS_EXACT:-${LAB_MIN_CPUS:-2}}"
[ "$CPU_COUNT" -eq "$LAB_CPUS_EXACT" ] \
  || fail "cardinality error: lab ${LAB_ID} requires exactly ${LAB_CPUS_EXACT} CPU(s) (--cpus), got ${CPU_COUNT} (${CPUS})"
case "$CPU_COUNT" in
  1) CPU_KIND="single" ;;
  2) CPU_KIND="pair" ;;
  4) CPU_KIND="quad" ;;
  8) CPU_KIND="octet" ;;
  *) CPU_KIND="set-${CPU_COUNT}" ;;
esac
CPU_A="${CPU_LIST[0]}"
# CPU_B exists only for pair-or-larger labs — single-core labs never read
# or define a second CPU (and never invoke pair-specific code paths).
CPU_B=""
[ "$CPU_COUNT" -ge 2 ] && CPU_B="${CPU_LIST[1]}"

if [ "$COMPONENT" = "rust-harness" ]; then
  [ -n "${LAB_RUST_DIR:-}" ] || fail "--component rust-harness: lab ${LAB_ID} has no Rust harness"
  RUST_ONLY_OK=0
  for V in $(effective_variants); do
    [ -n "$(lab_rust_evidence_cmd "$V")" ] && RUST_ONLY_OK=1
  done
  [ "$RUST_ONLY_OK" = "1" ] || fail "--component rust-harness: no selected variant defines a Rust harness command (harness-* variants are Java-only; pick the base variant, e.g. cached-b1-c1024)"
fi

# --- Resolved publication profile -------------------------------------------
# Explicit values, stored verbatim in benchmark-profile.json. Multiple
# independent JVM forks are mandatory — one long invocation is not a
# substitute for fork-to-fork variance.
#
# publication-core: full statistical rigor on a representative subset of
#   variants (the profiler-policy layer above decides which variants get
#   c2c; every variant still gets the full JMH/perf-stat rigor below).
# publication-sweep: the full parameter matrix, same JMH rigor, lightweight
#   (single-repetition) perf stat, and c2c is never run per matrix point
#   (resolve_profiler_action forces RUN_C2C=0 for this profile).
# "publication" is accepted as a deprecated alias for publication-core so
# existing invocations/docs keep working; PROFILE is normalized below so
# every downstream artifact records the canonical name.
case "$PROFILE" in
  publication) PROFILE="publication-core" ;;
esac
case "$PROFILE" in
  publication-core)
    EV_FORKS=5; EV_WI=5; EV_W="1s"; EV_I=10; EV_R="1s"; EV_TIMEOUT_SECONDS=1800; PERF_STAT_REPS=3
    ;;
  publication-sweep)
    EV_FORKS=3; EV_WI=3; EV_W="1s"; EV_I=8; EV_R="1s"; EV_TIMEOUT_SECONDS=1200; PERF_STAT_REPS=1
    ;;
  full)
    EV_FORKS=2; EV_WI=3; EV_W="1s"; EV_I=5; EV_R="1s"; EV_TIMEOUT_SECONDS=900; PERF_STAT_REPS=3
    ;;
  development)
    EV_FORKS=1; EV_WI=2; EV_W="500ms"; EV_I=3; EV_R="500ms"; EV_TIMEOUT_SECONDS=420; PERF_STAT_REPS=1
    ;;
  smoke)
    EV_FORKS=1; EV_WI=0; EV_W="200ms"; EV_I=1; EV_R="200ms"; EV_TIMEOUT_SECONDS=180; PERF_STAT_REPS=1
    ;;
  *) fail "unsupported profile '${PROFILE}' (publication-core|publication-sweep|full|development|smoke)" ;;
esac
# GC/JVM choice for evidence runs: these benchmarks allocate nothing in the
# measured path, so the collector's job is trivial — SerialGC is chosen to
# minimize auxiliary JVM worker threads (no parallel GC gangs migrating
# across the pinned CPU pair and inflating process-level migration counts).
# Heap safety is demonstrated, not assumed: the correctness gate runs the
# full suite under the same fixed 1g heap before any measurement, and a
# fork that OOMs fails the run. -XX:ActiveProcessorCount is set per
# scenario to the pinned CPU count so the JVM sizes its internal pools for
# the CPUs it actually has, not the whole host. This was not changed to
# make a result prettier: worker-level migration policy (below) is what
# gates publication; the GC choice only reduces unrelated JVM noise, and
# both GC selection and the resolved compiler/processor counts are captured
# in toolchain.json for review.
EV_JVM_ARGS_BASE="-Xms1g -Xmx1g -XX:+UseSerialGC --enable-native-access=ALL-UNNAMED"
# Labs may extend/override the counter set (LAB_PERF_EVENTS in the conf,
# e.g. LLC events for cache-hierarchy); unsupported counters are recorded
# as <not supported>, never discarded.
EV_PERF_EVENTS="${LAB_PERF_EVENTS:-cycles,instructions,cache-references,cache-misses,branches,branch-misses,task-clock,context-switches,cpu-migrations,page-faults}"
# Process-level aggregate migrations are host/JVM-noise evidence, recorded
# and bounded separately; benchmark-WORKER migrations (worker-placement
# evidence) are the blocking publication gate. The aggregate threshold only
# becomes blocking when worker pinning is unavailable.
MAX_AGGREGATE_MIGRATIONS_PER_SEC=50
MAX_LOAD_PER_CORE_X100=20
FAILURE_POLICY="abort-on-first-error"
export EV_SELECTOR EV_FORKS EV_WI EV_W EV_I EV_R EV_PERF_EVENTS

# --- Storage thresholds (docs/evidence-storage-retention.md) -----------------
# Every default is overridable via environment (run-all-benchmarks.sh
# resolves these from the host config's `storage:` section and exports
# them before invoking this runner; a standalone invocation gets the
# hardcoded defaults below, never an unbounded assumption).
MIN_AVAILABLE_BYTES="$(storage_default_abort_threshold_bytes)"
ABORT_THRESHOLD_BYTES="$(storage_default_abort_threshold_bytes)"
MAX_RUN_BYTES="$(storage_default_max_batch_bytes)"
MAX_RAW_PROFILER_BYTES_PER_VARIANT="$(storage_default_max_raw_profiler_bytes_per_variant)"
MAX_TEXT_REPORT_BYTES="$(storage_default_max_text_report_bytes)"
SMOKE_BUDGET_BYTES="$(storage_default_smoke_budget_bytes)"

JAVA_DIR="${REPO_ROOT}/${LAB_JAVA_DIR}"
[ -d "$JAVA_DIR" ] || fail "lab config points at missing Java project ${LAB_JAVA_DIR}"
RUST_DIR=""
[ -n "${LAB_RUST_DIR:-}" ] && RUST_DIR="${REPO_ROOT}/${LAB_RUST_DIR}"
export EV_JAR="${JAVA_DIR}/target/benchmarks.jar"

RUN_ID="linux-$(date -u +%Y%m%dT%H%M%SZ)"
RUN_DIR="${OUT_ROOT}/${LAB_ID}/${RUN_ID}"

plan() { echo "[plan] $*"; }
run_or_plan() {
  if [ "$DRY_RUN" = "1" ]; then plan "$*"; else eval "$*"; fi
}

# --- Signal handling / child cleanup (§ termination safety) ------------------
# The runner never leaves benchmark JVMs behind: on INT/TERM (and on any
# exit path) the currently running measurement tree is terminated with
# SIGTERM first; SIGKILL is reserved for a tree that ignores SIGTERM for
# the grace period. CURRENT_CHILD_PID is maintained by run_with_deadline.
CURRENT_CHILD_PID=""
cleanup_children() {
  if [ -n "${CURRENT_CHILD_PID}" ] && kill -0 "$CURRENT_CHILD_PID" 2>/dev/null; then
    signal_process_tree TERM "$CURRENT_CHILD_PID"
    local grace=0 kill_after="${PLAB_TIMEOUT_KILL_AFTER_SECONDS:-30}"
    while kill -0 "$CURRENT_CHILD_PID" 2>/dev/null && [ "$grace" -lt "$kill_after" ]; do
      sleep 1; grace=$((grace + 1))
    done
    if kill -0 "$CURRENT_CHILD_PID" 2>/dev/null; then
      signal_process_tree KILL "$CURRENT_CHILD_PID"
    fi
    CURRENT_CHILD_PID=""
  fi
}
on_signal() {
  echo "run-linux-evidence: interrupted — terminating benchmark children (SIGTERM, bounded grace)" >&2
  cleanup_children
  if [ -d "${RUN_DIR:-}" ] && declare -f write_run_status >/dev/null 2>&1; then
    write_run_status "rejected" "interrupted-by-signal" 2>/dev/null || true
  fi
  trap - EXIT
  exit 130
}
trap on_signal INT TERM
trap cleanup_children EXIT

capture_or_unavailable() {
  # capture_or_unavailable <description-command...> — prints command output
  # or the literal string "unavailable" (never omits a field).
  local out
  if out=$("$@" 2>/dev/null) && [ -n "$out" ]; then
    printf '%s' "$out"
  else
    printf 'unavailable'
  fi
}

json_escape() {
  # minimal JSON string escaper for captured text values
  python3 - "$1" <<'PYEOF' 2>/dev/null || printf '"unavailable"'
import json, sys
print(json.dumps(sys.argv[1]))
PYEOF
}

# --- Run-status / rejection / timeout helpers (defined early: also used by
# the filesystem preflight, below, not only by post-measurement paths) -------
write_run_status() {
  cat > "${RUN_DIR}/run-status.json" <<STATEOF
{
  "runStatus": "$1",
  "publicationEligible": $([ "$1" = "collected" ] && echo "${PUBLICATION_ELIGIBLE:-false}" || echo "false"),
  "canonicalEvidenceEligible": $([ "$1" = "collected" ] && echo "true" || echo "false"),
  "rejectionReason": $([ -n "${2:-}" ] && json_escape "$2" || printf 'null')
}
STATEOF
}

# cleanup_raw_profiler_files_in <dir> — deletes any raw-profiler artifact
# (perf.data, perf-c2c.data, JFR recordings, the unbounded pre-summary c2c
# text dump) still present under <dir>, unless retention was requested.
# Used for BOTH the rejected and timed-out terminal paths (section 7:
# "Failed and partial runs must not preserve unlimited raw profiler data by
# default").
cleanup_raw_profiler_files_in() {
  local dir="$1" retain="$2"
  [ "$retain" = "1" ] && return 0
  [ -d "$dir" ] || return 0
  find "$dir" \( -name 'perf.data' -o -name 'perf-c2c.data' -o -name '*.jfr' -o -name '.perf-c2c-report-raw.txt' \) -type f -delete 2>/dev/null || true
}

mark_rejected() {
  echo "run-linux-evidence: RUN REJECTED — $1" >&2
  write_run_status "rejected" "$1"
  cleanup_raw_profiler_files_in "$RUN_DIR" "$RETAIN_FAILED_RAW"
  write_sha256sums "$RUN_DIR" >/dev/null 2>&1 || true
  echo "Partial artifacts preserved as DIAGNOSTIC ONLY in ${RUN_DIR} (run-status.json: rejected)." >&2
  exit 1
}

# A fired wall-clock timeout is its own terminal state: the benchmark hung
# (the SPSC producer spun 14h40m in batch-20260717T150131Z). jcmd/thread-
# dump diagnostics were captured before termination; the run is
# diagnostic-only and the distinct exit code 3 makes batch orchestration
# abort rather than continue past a hang.
mark_timeout() {
  echo "run-linux-evidence: BENCHMARK TIMEOUT — $1 exceeded the ${EV_TIMEOUT_SECONDS}s hard wall-clock budget" >&2
  write_run_status "failed-benchmark-timeout" "$1"
  cleanup_raw_profiler_files_in "$RUN_DIR" "$RETAIN_FAILED_RAW"
  write_sha256sums "$RUN_DIR" >/dev/null 2>&1 || true
  echo "Hang diagnostics (jcmd thread dumps, affinity, process tree) preserved in ${VDIR:-$RUN_DIR} (timeout-*.txt, timeout-diagnostics.json)." >&2
  echo "Artifacts are DIAGNOSTIC ONLY (run-status.json: failed-benchmark-timeout)." >&2
  exit 3
}

# mark_storage_abort <reason-code> <message> — section 5/6: a crossed
# storage threshold is its own terminal state, never "benchmark
# instability". Stops the current profiler/child, finalizes a partial
# diagnostic manifest, removes temporary files, preserves bounded
# diagnostics, and exits 4 (a distinct code so batch orchestration aborts
# the whole batch exactly as it does for exit 3 timeouts).
mark_storage_abort() {
  local reason_code="$1" message="$2"
  echo "run-linux-evidence: STORAGE ABORT (${reason_code}) — ${message}" >&2
  cleanup_children
  mkdir -p "$RUN_DIR" 2>/dev/null || true
  write_run_status "$reason_code" "$message"
  cleanup_raw_profiler_files_in "$RUN_DIR" "$RETAIN_FAILED_RAW"
  find "$RUN_DIR" -name '*.tmp' -o -name '.perf-c2c-report-raw.txt' 2>/dev/null | xargs -r rm -f 2>/dev/null || true
  write_sha256sums "$RUN_DIR" >/dev/null 2>&1 || true
  echo "Bounded diagnostic manifest preserved in ${RUN_DIR} (run-status.json: ${reason_code})." >&2
  exit 4
}

# check_storage_or_abort <label> — section 6 checkpoint: fails the run
# (mark_storage_abort) if the output filesystem is below the abort
# threshold or this run has exceeded its total-bytes budget. Called before
# every variant, after every profiler invocation, after every variant and
# before archive generation.
check_storage_or_abort() {
  local label="$1"
  [ "$DRY_RUN" = "1" ] && return 0
  local fs_avail run_bytes raw_bytes within=1
  fs_avail="$(fs_available_bytes "$OUT_ROOT")"
  run_bytes="$(dir_size_bytes "$RUN_DIR")"
  raw_bytes=0
  [ -d "$RUN_DIR" ] && raw_bytes="$(python3 -c "
import sys
sys.path.insert(0, '${SCRIPT_DIR}/lib')
from evidence_classify import classify
import os
total = 0
for root, _dirs, files in os.walk('${RUN_DIR}'):
    for name in files:
        fp = os.path.join(root, name)
        rel = os.path.relpath(fp, '${RUN_DIR}')
        if classify(rel) == 'raw-profiler':
            try:
                total += os.lstat(fp).st_size
            except OSError:
                pass
print(total)
")"
  if [ "$fs_avail" -lt "$ABORT_THRESHOLD_BYTES" ] || [ "$run_bytes" -gt "$MAX_RUN_BYTES" ]; then
    within=0
  fi
  if [ -d "$RUN_DIR" ]; then
    append_storage_progress "${RUN_DIR}/storage-progress.jsonl" "$run_bytes" "$run_bytes" "$raw_bytes" "$fs_avail" "$within"
  fi
  if [ "$within" = "0" ]; then
    if [ "$fs_avail" -lt "$ABORT_THRESHOLD_BYTES" ]; then
      mark_storage_abort "failed-storage-budget" "filesystem available (${fs_avail} bytes) fell below the abort threshold (${ABORT_THRESHOLD_BYTES} bytes) at: ${label}"
    else
      mark_storage_abort "failed-storage-budget" "run exceeded its total-bytes budget (${run_bytes} > ${MAX_RUN_BYTES} bytes) at: ${label}"
    fi
  fi
}

# =============================================================================
# Preflight — every check below must pass BEFORE any measurement starts.
# =============================================================================
echo "== preflight"

# 1. Native Linux only.
KERNEL="$(uname -s)"
if [ "$KERNEL" != "Linux" ] && [ "$DRY_RUN" != "1" ]; then
  fail "this runner collects publication evidence on native Linux only (uname -s: ${KERNEL}). On macOS use the smoke workflow in content/labs/false-sharing/benchmark.md."
fi

# 2. Virtualization/container detection. Exit status is the source of
#    truth (detect_virtualization, lib/evidence-lib.sh): on a physical host
#    systemd-detect-virt prints "none" AND exits 1 — the literal "none" is
#    never classified as virtualization. Physical hosts are accepted.
VIRT_DETECTED="false"
VM_TYPE=""
CONTAINER_TYPE=""
ENV_KIND="physical"
PUBLICATION_ELIGIBLE="true"
# smoke/development profiles are wiring/diagnosis runs: never publication
# evidence regardless of host; a focused --component run likewise.
PUBLICATION_INELIGIBLE_REASON=""
case "$PROFILE" in
  smoke|development) PUBLICATION_ELIGIBLE="false"; PUBLICATION_INELIGIBLE_REASON="non-publication profile (${PROFILE})" ;;
esac
if [ "$COMPONENT" != "all" ]; then
  PUBLICATION_ELIGIBLE="false"
  PUBLICATION_INELIGIBLE_REASON="${PUBLICATION_INELIGIBLE_REASON:+${PUBLICATION_INELIGIBLE_REASON}; }focused component run (${COMPONENT})"
fi
if VIRT_INFO="$(detect_virtualization)"; then
  VIRT_DETECTED="true"
  ENV_KIND="virtualized"
  PUBLICATION_ELIGIBLE="false"
  PUBLICATION_INELIGIBLE_REASON="${PUBLICATION_INELIGIBLE_REASON:+${PUBLICATION_INELIGIBLE_REASON}; }virtualized environment"
  VM_TYPE="$(printf '%s' "$VIRT_INFO" | sed -n 's/^vm=\([^ ]*\).*/\1/p')"
  CONTAINER_TYPE="$(printf '%s' "$VIRT_INFO" | sed -n 's/.*container=\(.*\)$/\1/p')"
  [ "$VM_TYPE" = "none" ] && VM_TYPE=""
  [ "$CONTAINER_TYPE" = "none" ] && CONTAINER_TYPE=""
  if [ "$ALLOW_VIRTUALIZED" = "1" ]; then
    # Smoke/development wiring checks only — a virtualized environment can
    # never produce publication evidence, so publication-grade profiles are
    # rejected outright even with the flag.
    case "$PROFILE" in
      smoke|development) echo "   virtualization detected (${VIRT_INFO}) — continuing in ${PROFILE} smoke mode; NOT publication eligible" ;;
      *) fail "--allow-virtualized cannot be used with profile '${PROFILE}' — virtualized environments are never publication evidence (smoke|development only)" ;;
    esac
  elif [ "$DRY_RUN" != "1" ]; then
    fail "virtualization/emulation detected (${VIRT_INFO}) — VMs, containers and emulators are never publication evidence (--allow-virtualized permits smoke|development wiring checks only)"
  fi
else
  VIRT_INFO="none"
fi

# 3. perf capabilities.
PERF_VERSION="unavailable"; PERF_STAT_OK="false"; PERF_C2C_OK="false"; PERF_MEM_EVENTS="unavailable"
PERF_PARANOID="unavailable"; NMI_WATCHDOG="unavailable"
if command -v perf >/dev/null 2>&1; then
  PERF_VERSION="$(capture_or_unavailable perf --version)"
  if perf stat -x, -e cycles -- true >/dev/null 2>&1; then PERF_STAT_OK="true"; fi
  # `perf c2c record -e list` prints the memory events c2c would use; it
  # fails on hardware/kernels without the required load-latency PMU support.
  if PERF_MEM_EVENTS="$(perf c2c record -e list 2>&1)" && [ -n "$PERF_MEM_EVENTS" ]; then
    PERF_C2C_OK="true"
  else
    PERF_C2C_OK="false"
  fi
fi
[ -r /proc/sys/kernel/perf_event_paranoid ] && PERF_PARANOID="$(cat /proc/sys/kernel/perf_event_paranoid)"
[ -r /proc/sys/kernel/nmi_watchdog ] && NMI_WATCHDOG="$(cat /proc/sys/kernel/nmi_watchdog)"

if [ "$DRY_RUN" != "1" ]; then
  [ "$PERF_VERSION" != "unavailable" ] || fail "perf is not installed"
  [ "$PERF_STAT_OK" = "true" ] || fail "perf stat cannot count events as the invoking user (perf_event_paranoid=${PERF_PARANOID}) — one-time host setup: sudo sysctl kernel.perf_event_paranoid=-1 (never run this whole script under sudo)"
  if [ "${LAB_C2C_REQUIRED:-0}" = "1" ]; then
    [ "$PERF_C2C_OK" = "true" ] || fail "perf c2c cannot open its required memory events on this CPU/kernel — not every x86_64 part supports useful c2c evidence; this host cannot produce the HITM evidence this lab requires"
  fi
fi

# 4. Topology.
LSCPU_E_FILE="$(mktemp)"
if command -v lscpu >/dev/null 2>&1; then
  lscpu -e=CPU,CORE,SOCKET,NODE,ONLINE > "$LSCPU_E_FILE" 2>/dev/null || fail "lscpu -e failed"
else
  [ "$DRY_RUN" = "1" ] || fail "lscpu is unavailable"
fi
if [ -s "$LSCPU_E_FILE" ]; then
  # Every pair in the selected set must satisfy the placement policy —
  # distinct physical cores, no SMT siblings, same socket/node unless the
  # cross-socket scenario was explicitly requested.
  for ((ti = 0; ti < ${#CPU_LIST[@]}; ti++)); do
    for ((tj = ti + 1; tj < ${#CPU_LIST[@]}; tj++)); do
      topo_validate_cpus "$LSCPU_E_FILE" "${CPU_LIST[ti]}" "${CPU_LIST[tj]}" "$ALLOW_CROSS_SOCKET" >/dev/null \
        || fail "CPU selection rejected (pair ${CPU_LIST[ti]},${CPU_LIST[tj]})"
    done
  done
  if [ "$CPU_KIND" = "single" ]; then
    # Single-core lab: only existence/online validation — pair topology
    # (distinct cores, SMT siblings, socket/NUMA policy) does not apply
    # and is never invoked.
    TOPO_RESULT="$(topo_validate_single "$LSCPU_E_FILE" "$CPU_A")" || fail "CPU selection rejected"
    SCENARIO="single"
  else
    TOPO_RESULT="$(topo_validate_cpus "$LSCPU_E_FILE" "$CPU_A" "$CPU_B" "$ALLOW_CROSS_SOCKET")"
    SCENARIO="$(topo_scenario "$LSCPU_E_FILE" "$CPU_A" "$CPU_B")"
  fi
  echo "   topology: ${TOPO_RESULT} (scenario: ${SCENARIO})"
else
  TOPO_RESULT="unavailable (dry-run on non-Linux host)"
  SCENARIO="unknown"
fi

# 5. Source revision.
GIT_REVISION="$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null)" || fail "cannot identify the source revision (not a git checkout?)"
GIT_DIRTY="false"
GIT_DIFF_HASH="null"
if [ -n "$(git -C "$REPO_ROOT" status --porcelain 2>/dev/null)" ]; then
  GIT_DIRTY="true"
  # A dirty tree must hash its diff (plab-003 schema rule) so the canonical
  # records can prove exactly which uncommitted state was measured.
  if command -v sha256sum >/dev/null 2>&1; then
    GIT_DIFF_HASH="\"$(git -C "$REPO_ROOT" diff HEAD | sha256sum | awk '{print $1}')\""
  else
    GIT_DIFF_HASH="\"$(git -C "$REPO_ROOT" diff HEAD | shasum -a 256 | awk '{print $1}')\""
  fi
fi

# 6. Background load.
if [ "$SKIP_LOAD_CHECK" != "1" ] && [ -r /proc/loadavg ]; then
  NPROC="$(nproc 2>/dev/null || echo 1)"
  check_load /proc/loadavg "$NPROC" "$MAX_LOAD_PER_CORE_X100" >/dev/null \
    || fail "host is under material background load (rerun when idle, or --skip-load-check for a non-publication run)"
fi

# 7. Output directory writable.
mkdir -p "$RUN_DIR" 2>/dev/null || fail "cannot create output directory ${RUN_DIR}"
[ -w "$RUN_DIR" ] || fail "output directory ${RUN_DIR} is not writable"

# 7b. Filesystem preflight (section 5 — docs/evidence-storage-retention.md):
# checked BEFORE any build/measurement starts. A physical host under real
# load is exactly the scenario the 2026-07 incident happened on — refusing
# to start a run when the disk is already nearly full is cheap; discovering
# it mid-batch after hours of measurement is what filled it to zero.
FS_STATS_JSON="$(fs_stats_json "$OUT_ROOT")"
cat > "${RUN_DIR}/filesystem-preflight.json" <<FSEOF
$(printf '%s' "$FS_STATS_JSON" | python3 -c "
import json, sys
stats = json.load(sys.stdin)
stats['minimumRequiredBytes'] = ${MIN_AVAILABLE_BYTES}
stats['withinBudget'] = stats['availableBytes'] >= ${MIN_AVAILABLE_BYTES}
stats['repoRootDevice'] = None
try:
    import os
    stats['sameFilesystemAsRepo'] = os.stat('${OUT_ROOT}').st_dev == os.stat('${REPO_ROOT}').st_dev
except OSError:
    stats['sameFilesystemAsRepo'] = None
print(json.dumps(stats, indent=2))
")
FSEOF
FS_WITHIN_BUDGET="$(python3 -c "import json; print(json.load(open('${RUN_DIR}/filesystem-preflight.json'))['withinBudget'])")"
if [ "$FS_WITHIN_BUDGET" != "True" ] && [ "$DRY_RUN" != "1" ]; then
  write_run_status "rejected" "failed-storage-preflight"
  echo "run-linux-evidence: failed-storage-preflight — ${OUT_ROOT} has insufficient available space (see ${RUN_DIR}/filesystem-preflight.json, minimum required ${MIN_AVAILABLE_BYTES} bytes)" >&2
  exit 1
fi
SAME_FS_AS_REPO="$(python3 -c "import json; v=json.load(open('${RUN_DIR}/filesystem-preflight.json')).get('sameFilesystemAsRepo'); print('true' if v else 'false')")"
[ "$SAME_FS_AS_REPO" = "true" ] && echo "   NOTE: results root ${OUT_ROOT} is on the SAME filesystem as the repository — recorded in filesystem-preflight.json"

# 8. Java/Maven toolchain present and actually runnable — `command -v`
#    alone is fooled by broken shims (e.g. macOS's /usr/bin/java stub with
#    no JDK); executing `java -version` is the real check, and it resolves
#    through whatever PATH the host uses (SDKMAN installs included).
if [ "$DRY_RUN" != "1" ] && [ "$COMPONENT" = "all" ]; then
  java -version >/dev/null 2>&1 || fail "java is not installed"
  command -v mvn >/dev/null 2>&1 || fail "mvn (Maven) is not installed"
fi
if [ "$COMPONENT" = "rust-harness" ] && [ "$DRY_RUN" != "1" ]; then
  command -v cargo >/dev/null 2>&1 || fail "cargo is not installed (required for --component rust-harness)"
fi

# 9. Correctness gate — measurement never starts on a failing suite.
echo "== correctness gate (mvn test)"
CORRECTNESS_STATUS="skipped-dry-run"
if [ "$COMPONENT" = "rust-harness" ]; then
  # Rust-only diagnosis loop: the Java suite gates full evidence runs, not
  # this focused mode; the Rust harness carries its own sequence check.
  CORRECTNESS_STATUS="skipped-rust-harness-component"
  echo "   skipped (component=rust-harness — Rust harness self-validates sequences and placement)"
elif [ "$DRY_RUN" != "1" ]; then
  if (cd "$JAVA_DIR" && mvn -q test) > "${RUN_DIR}/correctness-console.log" 2>&1; then
    CORRECTNESS_STATUS="passed"
  else
    CORRECTNESS_STATUS="failed"
    tail -40 "${RUN_DIR}/correctness-console.log" >&2
    fail "correctness gate failed — no measurement is collected on a failing suite"
  fi
  if [ "$PREFLIGHT_ONLY" != "1" ]; then
    echo "== build benchmarks.jar"
    (cd "$JAVA_DIR" && mvn -q -DskipTests package) >> "${RUN_DIR}/correctness-console.log" 2>&1 \
      || fail "mvn package failed"
    [ -f "$EV_JAR" ] || fail "benchmarks.jar missing after build"
  fi
fi

# =============================================================================
# Metadata capture
# =============================================================================
echo "== capturing environment metadata"

{
  echo "===== uname -a"
  capture_or_unavailable uname -a; echo
  echo "===== lscpu"
  capture_or_unavailable lscpu; echo
  echo "===== lscpu -e=CPU,CORE,SOCKET,NODE,ONLINE"
  cat "$LSCPU_E_FILE" 2>/dev/null || echo "unavailable"
  echo "===== /proc/cpuinfo"
  cat /proc/cpuinfo 2>/dev/null || echo "unavailable"
  echo "===== /proc/cmdline"
  cat /proc/cmdline 2>/dev/null || echo "unavailable"
} > "${RUN_DIR}/topology.txt"

governor_for() { capture_or_unavailable cat "/sys/devices/system/cpu/cpu$1/cpufreq/scaling_governor"; }
freq_for() { capture_or_unavailable cat "/sys/devices/system/cpu/cpu$1/cpufreq/$2"; }

TURBO="unavailable"
[ -r /sys/devices/system/cpu/intel_pstate/no_turbo ] && TURBO="no_turbo=$(cat /sys/devices/system/cpu/intel_pstate/no_turbo)"
[ -r /sys/devices/system/cpu/cpufreq/boost ] && TURBO="boost=$(cat /sys/devices/system/cpu/cpufreq/boost)"
SMT_STATE="$(capture_or_unavailable cat /sys/devices/system/cpu/smt/control)"
MICROCODE="$(awk -F': ' '/^microcode/ { print $2; exit }' /proc/cpuinfo 2>/dev/null || true)"
[ -n "${MICROCODE:-}" ] || MICROCODE="unavailable"
NUMA_TOPOLOGY="$(capture_or_unavailable numactl --hardware)"
LOAD_SNAPSHOT="$(capture_or_unavailable cat /proc/loadavg)"

JAVA_VERSION="$(capture_or_unavailable bash -c 'java -version 2>&1 | head -3')"
MVN_VERSION="$(capture_or_unavailable bash -c 'mvn --version 2>/dev/null | head -1')"
JMH_VERSION="$(grep -o '<jmh.version>[^<]*</jmh.version>' "${JAVA_DIR}/pom.xml" | sed 's/<[^>]*>//g' || echo unavailable)"
RUSTC_VERSION="$(capture_or_unavailable rustc --version)"
CARGO_VERSION="$(capture_or_unavailable cargo --version)"
# Resolved JVM thread-pool sizing under the evidence flags — captured, not
# assumed (compiler thread count and active processor count in particular).
JVM_RESOLVED_FLAGS="$(capture_or_unavailable bash -c "java ${EV_JVM_ARGS_BASE} -XX:ActiveProcessorCount=2 -XX:+PrintFlagsFinal -version 2>/dev/null | grep -E 'CICompilerCount|ActiveProcessorCount|UseSerialGC' | tr -s ' '")"

cat > "${RUN_DIR}/environment.json" <<ENVEOF
{
  "runner": "run-linux-evidence.sh",
  "labId": "${LAB_ID}",
  "runId": "${RUN_ID}",
  "capturedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "kernel": $(json_escape "$(capture_or_unavailable uname -a)"),
  "virtualization": { "detected": ${VIRT_DETECTED}, "vmType": $(if [ -n "$VM_TYPE" ]; then json_escape "$VM_TYPE"; else printf 'null'; fi), "containerType": $(if [ -n "$CONTAINER_TYPE" ]; then json_escape "$CONTAINER_TYPE"; else printf 'null'; fi), "environmentKind": "${ENV_KIND}", "publicationEligible": ${PUBLICATION_ELIGIBLE} },
  "selectedCpus": { "kind": "${CPU_KIND}", "list": "${CPUS}", "validation": $(json_escape "$TOPO_RESULT"), "scenario": $(json_escape "$SCENARIO") },
  "cpuDetails": [$(FIRST=1; for C in "${CPU_LIST[@]}"; do
    [ "$FIRST" = "1" ] || printf ', '
    FIRST=0
    printf '{ "cpu": %s, "governor": %s, "freqMin": %s, "freqMax": %s, "freqCur": %s }' \
      "$C" "$(json_escape "$(governor_for "$C")")" \
      "$(json_escape "$(freq_for "$C" scaling_min_freq)")" \
      "$(json_escape "$(freq_for "$C" scaling_max_freq)")" \
      "$(json_escape "$(freq_for "$C" scaling_cur_freq)")"
  done)],
  "turbo": $(json_escape "$TURBO"),
  "smt": $(json_escape "$SMT_STATE"),
  "numaTopology": $(json_escape "$NUMA_TOPOLOGY"),
  "microcode": $(json_escape "$MICROCODE"),
  "perfEventParanoid": $(json_escape "$PERF_PARANOID"),
  "nmiWatchdog": $(json_escape "$NMI_WATCHDOG"),
  "backgroundLoad": $(json_escape "$LOAD_SNAPSHOT"),
  "sourceCommit": "${GIT_REVISION}",
  "dirtyTree": ${GIT_DIRTY},
  "diffHash": ${GIT_DIFF_HASH},
  "outputRoot": $(json_escape "$OUT_ROOT"),
  "outputFilesystem": $(cat "${RUN_DIR}/filesystem-preflight.json")
}
ENVEOF

cat > "${RUN_DIR}/toolchain.json" <<TOOLEOF
{
  "java": $(json_escape "$JAVA_VERSION"),
  "maven": $(json_escape "$MVN_VERSION"),
  "jmh": $(json_escape "$JMH_VERSION"),
  "rustc": $(json_escape "$RUSTC_VERSION"),
  "cargo": $(json_escape "$CARGO_VERSION"),
  "jvmArgsBase": $(json_escape "$EV_JVM_ARGS_BASE"),
  "jvmArgsNote": "per-scenario additions: -XX:ActiveProcessorCount=<pinned cpu count>, worker pin properties (-Dplab.*), placement dir — recorded per variant in benchmark-profile.json and the JMH json's jvmArgs",
  "jvmResolvedFlags": $(json_escape "$JVM_RESOLVED_FLAGS"),
  "benchmarkSelector": $(json_escape "$EV_SELECTOR")
}
TOOLEOF

cat > "${RUN_DIR}/capabilities.json" <<CAPEOF
{
  "perfVersion": $(json_escape "$PERF_VERSION"),
  "perfStat": ${PERF_STAT_OK},
  "perfC2c": ${PERF_C2C_OK},
  "perfC2cEventList": $(json_escape "$PERF_MEM_EVENTS"),
  "perfEventParanoid": $(json_escape "$PERF_PARANOID"),
  "virtualization": { "detected": ${VIRT_DETECTED}, "vmType": $(if [ -n "$VM_TYPE" ]; then json_escape "$VM_TYPE"; else printf 'null'; fi), "containerType": $(if [ -n "$CONTAINER_TYPE" ]; then json_escape "$CONTAINER_TYPE"; else printf 'null'; fi), "environmentKind": "${ENV_KIND}", "publicationEligible": ${PUBLICATION_ELIGIBLE} },
  "symbolization": "jvm-symbols-not-guaranteed (recorded separately; source-line attribution is not required for HITM evidence)",
  "requestedEvents": $(json_escape "$EV_PERF_EVENTS"),
  "notes": "unsupported counters appear as <not supported> rows in the perf-stat CSVs — recorded, never discarded"
}
CAPEOF

cat > "${RUN_DIR}/benchmark-profile.json" <<PROFEOF
{
  "name": "${PROFILE}",
  "jmh": {
    "forks": ${EV_FORKS},
    "warmupIterations": ${EV_WI},
    "warmupTime": "${EV_W}",
    "measurementIterations": ${EV_I},
    "measurementTime": "${EV_R}",
    "threadsPerScenario": {$(FIRST=1; for V in $(lab_variants); do [ "$FIRST" = "1" ] || printf ', '; FIRST=0; printf '"%s": %s' "$V" "$(lab_threads "$V")"; done)},
    "selector": $(json_escape "$EV_SELECTOR")
  },
  "jvm": {
    "argsBase": $(json_escape "$EV_JVM_ARGS_BASE"),
    "gc": "SerialGC — allocation-free measured paths; minimizes auxiliary JVM worker threads so process-level migration counts reflect the host, not GC gangs; heap safety demonstrated by the correctness gate under the same fixed 1g heap",
    "activeProcessorCount": "set per scenario to the pinned CPU count",
    "workerPinning": "sched_setaffinity per worker thread via FFM (plab.cpuA/plab.cpuB or plab.workerCpus), established at trial setup, verified at teardown"
  },
  "placement": {
    "cpus": "${CPUS}",
    "scenarioCpuMapping": {$(FIRST=1; for V in $(lab_variants); do [ "$FIRST" = "1" ] || printf ', '; FIRST=0; CNT=$(lab_cpu_count "$V"); MAP="${CPU_LIST[0]}"; for ((mi=1; mi<CNT; mi++)); do MAP="${MAP},${CPU_LIST[mi]}"; done; printf '"%s": "%s"' "$V" "$MAP"; done)},
    "workerMechanism": "per-thread sched_setaffinity (blocking policy: any pinned-worker migration or misplacement rejects the run; evidence in <variant>/worker-placement.json)",
    "processContainment": "taskset -c (secondary containment for the whole JVM — never treated as worker pinning)",
    "maxAggregateProcessMigrationsPerSecond": ${MAX_AGGREGATE_MIGRATIONS_PER_SEC},
    "aggregatePolicy": "host/JVM-noise evidence; blocking only when worker placement is unavailable",
    "scenario": $(json_escape "$SCENARIO")
  },
  "perfStatRepetitions": ${PERF_STAT_REPS},
  "profilerPolicy": {
    "labDefault": "${LAB_PROFILER_POLICY}",
    "perVariant": {$(FIRST=1; for V in $(lab_variants); do [ "$FIRST" = "1" ] || printf ', '; FIRST=0; resolve_profiler_action "$V"; printf '"%s": { "stat": %s, "c2c": %s, "policy": "%s" }' "$V" "$([ "$RUN_STAT" = "1" ] && echo true || echo false)" "$([ "$RUN_C2C" = "1" ] && echo true || echo false)" "$(variant_profiler_policy "$V")"; done)},
    "c2cRepresentativeVariants": $(json_escape "$(c2c_representative_variants)"),
    "note": "smoke and publication-sweep never run c2c regardless of lab policy; c2c-core-only runs c2c only for c2cRepresentativeVariants"
  },
  "storageThresholds": {
    "minAvailableBytes": ${MIN_AVAILABLE_BYTES},
    "abortThresholdBytes": ${ABORT_THRESHOLD_BYTES},
    "maxRunBytes": ${MAX_RUN_BYTES},
    "maxRawProfilerBytesPerVariant": ${MAX_RAW_PROFILER_BYTES_PER_VARIANT},
    "maxTextReportBytes": ${MAX_TEXT_REPORT_BYTES},
    "smokeBudgetBytes": ${SMOKE_BUDGET_BYTES},
    "rawProfilerRetained": $([ "$RETAIN_RAW_PROFILER_DATA" = "1" ] && echo true || echo false),
    "failedRawRetained": $([ "$RETAIN_FAILED_RAW" = "1" ] && echo true || echo false)
  },
  "failurePolicy": "${FAILURE_POLICY}",
  "loadPolicy": { "maxOneMinuteLoadPerCoreX100": ${MAX_LOAD_PER_CORE_X100} }
}
PROFEOF

cat > "${RUN_DIR}/correctness.json" <<COREOF
{
  "gate": "mvn test (${LAB_JAVA_DIR})",
  "status": "${CORRECTNESS_STATUS}",
  "console": "correctness-console.log"
}
COREOF

# =============================================================================
# Preflight-only stop point: every check above has run (OS, virtualization,
# topology, load, perf capabilities, toolchain, revision, correctness gate,
# output permissions, filesystem preflight); nothing below (JMH, perf stat,
# perf c2c, archive) executes. Metadata files stay in place for inspection.
# =============================================================================
if [ "$PREFLIGHT_ONLY" = "1" ]; then
  echo
  echo "Preflight passed."
  echo "Host type: ${ENV_KIND}"
  if [ "$PUBLICATION_ELIGIBLE" = "true" ]; then
    echo "Publication profile eligible: yes"
  else
    echo "Publication profile eligible: no (${PUBLICATION_INELIGIBLE_REASON:-virtualized — smoke/development wiring checks only})"
  fi
  echo "Selected CPUs: ${CPUS}"
  echo "Measurement was not started because --preflight-only was supplied."
  exit 0
fi

# run_measured <label> <command-string> — dry-run plans verbatim; real runs
# execute under the profile's hard wall-clock budget. Timeout → mark_timeout
# (exit 3); any other failure aborts as before (abort-on-first-error).
run_measured() {
  local label="$1" cmd="$2"
  if [ "$DRY_RUN" = "1" ]; then plan "$cmd"; return 0; fi
  local rc=0
  run_with_deadline "$EV_TIMEOUT_SECONDS" "$VDIR" "$label" "$cmd" || rc=$?
  if [ "$rc" = "124" ]; then
    mark_timeout "$label"
  elif [ "$rc" != "0" ]; then
    fail "measurement command failed (${label}, exit ${rc}) — see logs in ${VDIR}"
  fi
}

# summarize_c2c_report <raw-report> <bounded-report> — section 8: a `perf
# c2c report --show-all` dump can be megabytes of irrelevant kernel symbols.
# The bounded report keeps exactly what the importer and a human reviewer
# need: summary totals, top cache lines, top symbols, local/remote HITM
# counts — never the raw line-by-line dump.
summarize_c2c_report() {
  local raw="$1" bounded="$2"
  python3 - "$raw" "$bounded" <<'PYEOF'
import re, sys
raw_path, bounded_path = sys.argv[1], sys.argv[2]
with open(raw_path, errors="replace") as fh:
    lines = fh.readlines()

def grab_section(header_pattern, max_lines=25):
    out = []
    capturing = False
    for line in lines:
        if re.search(header_pattern, line):
            capturing = True
            out.append(line.rstrip("\n"))
            continue
        if capturing:
            if line.strip() == "" and len(out) > 1:
                break
            out.append(line.rstrip("\n"))
            if len(out) >= max_lines:
                break
    return out

summary = [l.rstrip("\n") for l in lines if re.search(r"Total.*(records|loads|stores)|HITM|Shared Cache Line", l, re.IGNORECASE)][:40]
top_cachelines = grab_section(r"Shared Data Cache Line Table", 25)
top_symbols = grab_section(r"Shared Cache Line Distribution Pareto|Symbol", 30)

with open(bounded_path, "w") as out:
    out.write("# Bounded perf c2c summary (docs/evidence-storage-retention.md section 8)\n")
    out.write(f"# Filtered from {len(lines)} raw line(s); the unbounded --show-all dump is never stored.\n\n")
    out.write("## Summary totals / HITM counts\n")
    out.write("\n".join(summary) if summary else "(no summary lines matched — see rawProfilerRetained if the full report was kept)")
    out.write("\n\n## Top cache lines\n")
    out.write("\n".join(top_cachelines) if top_cachelines else "(none captured)")
    out.write("\n\n## Top symbols\n")
    out.write("\n".join(top_symbols) if top_symbols else "(none captured)")
    out.write("\n")
PYEOF
}

for VARIANT in $(effective_variants); do
  VDIR="${RUN_DIR}/${VARIANT}"
  mkdir -p "$VDIR"
  echo "== variant: ${VARIANT}"
  check_storage_or_abort "before variant ${VARIANT}"

  # Per-scenario resolution from the lab config: thread count, the
  # deterministic CPU-list prefix this scenario uses, worker pin
  # properties, and the JMH parameter selection.
  VTHREADS="$(lab_threads "$VARIANT")"
  VCPU_COUNT="$(lab_cpu_count "$VARIANT")"
  SCEN_CPUS="${CPU_LIST[0]}"
  for ((ci = 1; ci < VCPU_COUNT; ci++)); do SCEN_CPUS="${SCEN_CPUS},${CPU_LIST[ci]}"; done
  export EV_CPUS="$SCEN_CPUS"
  export EV_THREADS="$VTHREADS"
  export EV_JMH_EXTRA="$(lab_jmh_args "$VARIANT")"
  export EV_JVM_ARGS="${EV_JVM_ARGS_BASE} -XX:ActiveProcessorCount=${VCPU_COUNT} $(lab_worker_props "$VARIANT") -Dplab.placementDir=${VDIR}"

  # Variant kind: "jmh" (default — full JMH evidence + perf stat + c2c) or
  # "aux" (a dedicated harness: litmus/outcome-count, warm-up trajectory,
  # backpressure — anything JMH's aggregate modes would misrepresent).
  if declare -f lab_variant_kind >/dev/null 2>&1; then
    VKIND="$(lab_variant_kind "$VARIANT")"
  else
    VKIND="jmh"
  fi
  resolve_profiler_action "$VARIANT"
  echo "   scenario kind=${VKIND} cpus=${SCEN_CPUS} threads=${VTHREADS} args='${EV_JMH_EXTRA}' hard-timeout=${EV_TIMEOUT_SECONDS}s/invocation profiler(stat=${RUN_STAT},c2c=${RUN_C2C})"

  if [ "$COMPONENT" = "rust-harness" ]; then
    RUST_CMD="$(lab_rust_evidence_cmd "$VARIANT")"
    if [ -z "$RUST_CMD" ]; then
      echo "-- component=rust-harness: variant ${VARIANT} has no Rust command — skipped"
      continue
    fi
    echo "-- Rust persistent-worker harness (component-only run)"
    RUST_RC=0
    if [ "$DRY_RUN" = "1" ]; then
      plan "(cd ${RUST_DIR} && perf stat -x, -e ${EV_PERF_EVENTS} -o ${VDIR}/rust-perf-stat.csv -- taskset -c ${SCEN_CPUS} ${RUST_CMD}) > ${VDIR}/rust-evidence.json 2> ${VDIR}/rust-evidence.log"
    else
      run_with_deadline "$EV_TIMEOUT_SECONDS" "$VDIR" "rust-harness (variant ${VARIANT})" \
        "(cd ${RUST_DIR} && perf stat -x, -e ${EV_PERF_EVENTS} -o ${VDIR}/rust-perf-stat.csv -- taskset -c ${SCEN_CPUS} ${RUST_CMD}) > ${VDIR}/rust-evidence.json 2> ${VDIR}/rust-evidence.log" || RUST_RC=$?
      if [ "$RUST_RC" = "124" ]; then
        mark_timeout "rust-harness (variant ${VARIANT})"
      elif [ "$RUST_RC" != "0" ]; then
        cat "${VDIR}/rust-evidence.json" >&2 2>/dev/null || true
        mark_rejected "rust-harness-violation (variant ${VARIANT}) — sequence/affinity/diagnostics failure, see ${VDIR}/rust-evidence.json"
      fi
    fi
    continue
  fi

  if [ "$VKIND" = "aux" ]; then
    AUX_CMD="$(lab_aux_evidence_cmd "$VARIANT")"
    [ -n "$AUX_CMD" ] || fail "variant ${VARIANT} is kind=aux but lab_aux_evidence_cmd returned nothing"
    # {VDIR} placeholder lets a config direct JVM logs (e.g. compilation
    # logs) into the variant's evidence directory.
    AUX_CMD="${AUX_CMD//\{VDIR\}/$VDIR}"
    AUX_DIR="${REPO_ROOT}/${LAB_AUX_DIR:-$LAB_JAVA_DIR}"
    echo "-- aux evidence harness"
    run_measured "aux-harness (variant ${VARIANT})" "(cd ${AUX_DIR} && perf stat -x, -e ${EV_PERF_EVENTS} -o ${VDIR}/jmh-placement.csv -- taskset -c ${SCEN_CPUS} ${AUX_CMD}) > ${VDIR}/aux-evidence.json 2> ${VDIR}/aux-evidence.log"
  else
    echo "-- JMH evidence run (${EV_FORKS} forks)"
    run_measured "jmh-evidence (variant ${VARIANT})" "$(build_jmh_evidence_command "$VARIANT" "$VDIR") > ${VDIR}/jmh-console.log 2>&1"

    if [ "$RUN_STAT" = "1" ]; then
      echo "-- perf stat (${PERF_STAT_REPS} repetition(s))"
      for REP in $(seq 1 "$PERF_STAT_REPS"); do
        run_measured "perf-stat r${REP} (variant ${VARIANT})" "$(build_perf_stat_command "$VARIANT" "$VDIR" "$REP") > ${VDIR}/perf-stat-console-r${REP}.log 2>&1"
      done
    else
      echo "-- perf stat: skipped (lab profiler policy = none)"
    fi

    if [ "$RUN_C2C" = "1" ] && [ "$PERF_C2C_OK" != "true" ] && [ "${LAB_C2C_REQUIRED:-0}" != "1" ]; then
      # Policy selected this variant for c2c, but the host cannot open the
      # required memory events (not every x86_64 part/kernel supports it)
      # and this lab does not hard-require c2c capability (a lab that DOES
      # — LAB_C2C_REQUIRED=1 — already failed this at preflight, step 3,
      # for any real run). Degrade gracefully instead of aborting the
      # whole run over an optional profiler.
      echo "-- perf c2c: policy selected variant ${VARIANT} but perf c2c is unavailable on this host — recorded as absent"
      RUN_C2C=0
    fi
    if [ "$RUN_C2C" = "1" ]; then
      echo "-- perf c2c record + bounded report"
      run_measured "perf-c2c-record (variant ${VARIANT})" "$(build_c2c_record_command "$VARIANT" "$VDIR") > ${VDIR}/perf-c2c-console.log 2>&1"
      if [ "$DRY_RUN" = "1" ]; then
        plan "$(build_c2c_report_command "$VARIANT" "$VDIR")"
      else
        # Per-variant raw-profiler size cap (section 5/12): a pathological
        # recording never gets the chance to eat the rest of the budget.
        RAW_C2C_BYTES="$(file_size_bytes "${VDIR}/perf-c2c.data")"
        if [ "$RAW_C2C_BYTES" -gt "$MAX_RAW_PROFILER_BYTES_PER_VARIANT" ]; then
          mark_rejected "failed-artifact-size-limit — ${VDIR}/perf-c2c.data is ${RAW_C2C_BYTES} bytes, exceeding the per-variant cap of ${MAX_RAW_PROFILER_BYTES_PER_VARIANT} bytes (variant ${VARIANT})"
        fi
        run_measured "perf-c2c-report (variant ${VARIANT})" "$(build_c2c_report_command "$VARIANT" "$VDIR") > ${VDIR}/.perf-c2c-report-raw.txt 2> ${VDIR}/perf-c2c-report.log"
        summarize_c2c_report "${VDIR}/.perf-c2c-report-raw.txt" "${VDIR}/perf-c2c-report.txt"
        if ! enforce_text_size_limit "${VDIR}/perf-c2c-report.txt" "$MAX_TEXT_REPORT_BYTES"; then
          mark_rejected "failed-artifact-size-limit — bounded c2c report for variant ${VARIANT} still exceeds ${MAX_TEXT_REPORT_BYTES} bytes"
        fi
        # Raw-profiler cleanup (section 7): hash + size are recorded BEFORE
        # any deletion decision; the raw file is removed by default only
        # once the bounded summary above exists and is non-empty.
        retire_raw_profiler_file "${VDIR}/perf-c2c.data" "${VDIR}/perf-c2c-report.txt" "$RETAIN_RAW_PROFILER_DATA" "${VDIR}/raw-profiler-retention.json" \
          || mark_rejected "raw-profiler cleanup failed for variant ${VARIANT} (see stderr)"
        rm -f "${VDIR}/.perf-c2c-report-raw.txt"
      fi
    else
      echo "-- perf c2c: not selected for this variant (policy=$(variant_profiler_policy "$VARIANT"), profile=${PROFILE}) — recorded as absent"
    fi
    check_storage_or_abort "after profiler, variant ${VARIANT}"
  fi

  RUST_CMD="$(lab_rust_evidence_cmd "$VARIANT")"
  if [ -n "$RUST_CMD" ]; then
    echo "-- Rust persistent-worker harness"
    if [ "$DRY_RUN" = "1" ]; then
      plan "(cd ${RUST_DIR} && perf stat -x, -e ${EV_PERF_EVENTS} -o ${VDIR}/rust-perf-stat.csv -- taskset -c ${SCEN_CPUS} ${RUST_CMD}) > ${VDIR}/rust-evidence.json 2> ${VDIR}/rust-evidence.log"
    else
      RUST_RC=0
      run_with_deadline "$EV_TIMEOUT_SECONDS" "$VDIR" "rust-harness (variant ${VARIANT})" \
        "(cd ${RUST_DIR} && perf stat -x, -e ${EV_PERF_EVENTS} -o ${VDIR}/rust-perf-stat.csv -- taskset -c ${SCEN_CPUS} ${RUST_CMD}) > ${VDIR}/rust-evidence.json 2> ${VDIR}/rust-evidence.log" || RUST_RC=$?
      if [ "$RUST_RC" = "124" ]; then
        mark_timeout "rust-harness (variant ${VARIANT})"
      elif [ "$RUST_RC" != "0" ]; then
        cat "${VDIR}/rust-evidence.json" >&2 2>/dev/null || true
        mark_rejected "rust-harness-violation (variant ${VARIANT}) — sequence/affinity/diagnostics failure, see ${VDIR}/rust-evidence.json"
      fi
    fi
  fi

  # --- Placement policy (after all of this variant's executions) -------------
  # Full-component runs only: it gates the JMH worker-placement files and
  # process-tree counters (processTreeCpuMigrations — launcher/service
  # threads included, never comparable with per-worker migration counters).
  # A rust-harness component run has no JMH placement artifacts; the Rust
  # harness enforces its own per-worker placement policy and already
  # rejected the run above on any violation.
  if [ "$DRY_RUN" != "1" ] && [ "$COMPONENT" = "all" ]; then
    # 1. Benchmark-WORKER migrations: BLOCKING. Merge the per-fork
    #    worker-placement files the benchmark wrote and validate every
    #    worker was pinned, landed on its intended CPU and never migrated.
    if ls "${VDIR}"/worker-placement-*.json >/dev/null 2>&1; then
      merge_worker_placement "$VDIR" \
        || mark_rejected "worker-placement-or-migration-policy (variant ${VARIANT})"
      WORKER_PLACEMENT_STATE="verified"
    else
      WORKER_PLACEMENT_STATE="unavailable"
    fi

    # 2. Aggregate JVM-process migrations (jmh-placement.csv): host/JVM
    #    noise evidence — recorded always; blocking only when worker-level
    #    evidence is unavailable (no pinning), since then it is the only
    #    placement signal we have.
    AGG_INFO="$(check_migrations "${VDIR}/jmh-placement.csv" "$MAX_AGGREGATE_MIGRATIONS_PER_SEC" 2>&1)" \
      && AGG_STATE="within-policy" || AGG_STATE="exceeded"
    cat > "${VDIR}/placement-policy.json" <<PLACEOF
{
  "workerPlacement": "${WORKER_PLACEMENT_STATE}",
  "workerMigrationPolicy": "blocking — any pinned-worker migration rejects the run",
  "aggregateProcessMigrations": $(json_escape "$AGG_INFO"),
  "aggregateState": "${AGG_STATE}",
  "aggregatePolicy": "host/JVM-noise evidence, max ${MAX_AGGREGATE_MIGRATIONS_PER_SEC}/s of task-clock; blocking only when worker placement is unavailable",
  "processContainment": "taskset -c ${SCEN_CPUS} (secondary containment only — never treated as worker pinning)"
}
PLACEOF
    if [ "$WORKER_PLACEMENT_STATE" = "unavailable" ] && [ "$AGG_STATE" = "exceeded" ]; then
      mark_rejected "aggregate-migration-policy-without-worker-pinning (variant ${VARIANT})"
    fi
  fi

  check_storage_or_abort "after variant ${VARIANT}"
done

write_run_status "collected" ""

# Smoke-profile budget (section 3): a smoke run must never exceed a small,
# configurable total footprint — it is a wiring/termination check, not
# evidence, and is permanently non-publication-eligible regardless.
if [ "$PROFILE" = "smoke" ] && [ "$DRY_RUN" != "1" ]; then
  SMOKE_RUN_BYTES="$(dir_size_bytes "$RUN_DIR")"
  if ! storage_budget_check "smoke-budget" "$SMOKE_RUN_BYTES" "$SMOKE_BUDGET_BYTES" > "${RUN_DIR}/smoke-budget.json"; then
    echo "run-linux-evidence: smoke run exceeded its ${SMOKE_BUDGET_BYTES}-byte budget (${SMOKE_RUN_BYTES} bytes) — see ${RUN_DIR}/smoke-budget.json" >&2
    write_run_status "rejected" "failed-storage-budget — smoke profile exceeded its configured budget"
    exit 1
  fi
fi

# =============================================================================
# Manifest, hashes, archive
# =============================================================================
echo "== manifest + hashes"

cat > "${RUN_DIR}/evidence-manifest.json" <<MANEOF
{
  "manifestVersion": 1,
  "labId": "${LAB_ID}",
  "runId": "${RUN_ID}",
  "runIdScheme": "linux-<utc-timestamp> (runner-side; plab-002 hash run-ids require node, which is not assumed on the measurement host)",
  "profile": "${PROFILE}",
  "scenario": $(json_escape "$SCENARIO"),
  "environmentKind": "${ENV_KIND}",
  "publicationEligible": ${PUBLICATION_ELIGIBLE},
  "sourceCommit": "${GIT_REVISION}",
  "dirtyTree": ${GIT_DIRTY},
  "diffHash": ${GIT_DIFF_HASH},
  "environment": "environment.json",
  "topology": "topology.txt",
  "capabilities": "capabilities.json",
  "toolchain": "toolchain.json",
  "benchmarkProfile": "benchmark-profile.json",
  "correctness": "correctness.json",
  "variants": {
$(FIRST=1; for V in $(effective_variants); do
  [ "$FIRST" = "1" ] || printf ',\n'
  FIRST=0
  printf '    "%s": {\n' "$V"
  printf '      "jmh": "%s/jmh.json",\n' "$V"
  printf '      "jmhPlacement": "%s/jmh-placement.csv",\n' "$V"
  printf '      "workerPlacement": "%s/worker-placement.json",\n' "$V"
  printf '      "placementPolicy": "%s/placement-policy.json",\n' "$V"
  printf '      "perfStat": ["%s/perf-stat.csv", "%s/perf-stat-r2.csv", "%s/perf-stat-r3.csv"],\n' "$V" "$V" "$V"
  printf '      "perfStatJmh": ["%s/perf-stat-jmh.json", "%s/perf-stat-jmh-r2.json", "%s/perf-stat-jmh-r3.json"],\n' "$V" "$V" "$V"
  printf '      "perfC2cReport": "%s/perf-c2c-report.txt",\n' "$V"
  printf '      "rawProfilerRetention": "%s/raw-profiler-retention.json",\n' "$V"
  printf '      "rustEvidence": "%s/rust-evidence.json"\n' "$V"
  printf '    }'
done; printf '\n')
  },
  "runStatusFile": "run-status.json",
  "variantSelection": $([ -n "$SELECTED_VARIANT" ] && printf '"focused:%s"' "$SELECTED_VARIANT" || printf '"all"'),
  "componentSelection": "${COMPONENT}",
  "hardTimeoutSecondsPerInvocation": ${EV_TIMEOUT_SECONDS},
  "outputRoot": $(json_escape "$OUT_ROOT"),
  "canonical": { "pendingImport": true, "importer": "scripts/performance-lab/import-evidence.sh (adds canonical-jmh.json, canonical-perf-stat.json, comparison.json on the repository machine)" },
  "review": { "verifiedMaturityRequiresHumanReview": true, "importDoesNotPromote": true }
}
MANEOF

if [ "$DRY_RUN" != "1" ]; then
  check_storage_or_abort "before archive generation"
  write_sha256sums "$RUN_DIR" || fail "hashing failed"
  # Default archive NEVER includes raw-profiler artifacts (section 11):
  # they are already deleted by default, and --exclude is a second,
  # belt-and-suspenders guarantee for the --retain-raw-profiler-data case
  # (retained on disk for local inspection, but never bundled into the
  # archive a maintainer copies off-host as "the" evidence package).
  TAR_EXCLUDES=(--exclude='*perf.data' --exclude='*perf-c2c.data' --exclude='*.jfr')
  ARCHIVE="${OUT_ROOT}/${LAB_ID}-${RUN_ID}-linux-evidence.tar"
  if command -v zstd >/dev/null 2>&1; then
    tar "${TAR_EXCLUDES[@]}" --zstd -cf "${ARCHIVE}.zst" -C "${OUT_ROOT}/${LAB_ID}" "${RUN_ID}"
    ARCHIVE="${ARCHIVE}.zst"
  else
    tar "${TAR_EXCLUDES[@]}" -czf "${ARCHIVE}.gz" -C "${OUT_ROOT}/${LAB_ID}" "${RUN_ID}"
    ARCHIVE="${ARCHIVE}.gz"
  fi
  # Separate, optional raw-profiler archive (section 11): only when raw
  # data was explicitly retained AND some actually exists. Paths are
  # relative-ized with sed (portable across GNU/BSD find — no reliance on
  # GNU find's -printf) so the archive members match the RUN_ID-rooted
  # layout the default archive uses.
  RAW_FILES="$(find "$RUN_DIR" \( -name 'perf.data' -o -name 'perf-c2c.data' \) 2>/dev/null | sed "s|^${RUN_DIR%/*}/||")"
  if [ "$RETAIN_RAW_PROFILER_DATA" = "1" ] && [ -n "$RAW_FILES" ]; then
    RAW_ARCHIVE="${OUT_ROOT}/${LAB_ID}-${RUN_ID}-raw-profiler.tar"
    if command -v zstd >/dev/null 2>&1; then
      printf '%s\n' "$RAW_FILES" | tar --zstd -cf "${RAW_ARCHIVE}.zst" -C "${OUT_ROOT}/${LAB_ID}" -T -
      RAW_ARCHIVE="${RAW_ARCHIVE}.zst"
    else
      printf '%s\n' "$RAW_FILES" | tar -czf "${RAW_ARCHIVE}.gz" -C "${OUT_ROOT}/${LAB_ID}" -T -
      RAW_ARCHIVE="${RAW_ARCHIVE}.gz"
    fi
    echo "Raw-profiler archive (explicitly requested, --retain-raw-profiler-data): ${RAW_ARCHIVE}"
  fi
  echo
  echo "== DONE"
  echo "Evidence archive: ${ARCHIVE}"
  echo
  echo "Copy it to the repository machine and run:"
  echo "  ./scripts/performance-lab/verify-evidence.sh ${ARCHIVE##*/}"
  echo "  ./scripts/performance-lab/import-evidence.sh ${ARCHIVE##*/}"
  echo
  echo "Importing does NOT promote the lab to verified — human review of the"
  echo "imported artifacts (docs/linux-evidence-runner.md, review checklist)"
  echo "is still required."
else
  echo
  echo "== DRY RUN COMPLETE (no measurement executed)"
  echo "Planned run directory: ${RUN_DIR}"
fi
