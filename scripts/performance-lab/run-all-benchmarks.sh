#!/usr/bin/env bash
# Canonical batch orchestrator: executes every benchmark-ready Performance
# Lab sequentially on the dedicated native-Linux host and produces one
# complete evidence package for later site-wide import.
#
#   ./scripts/performance-lab/run-all-benchmarks.sh \
#     --profile publication-core \
#     --host-config config/benchmark-hosts/precision-5810.yaml \
#     --repetitions 2
#
# Orchestrator ONLY: all benchmark execution and evidence logic lives in
# run-linux-evidence.sh (never duplicated here). Preflights every enabled
# lab before ANY measurement (one aggregated report; one blocked lab stops
# the batch), runs labs strictly sequentially with cooldown and host
# stability gates, gives every repetition its own run id / JVM+Rust
# processes / raw artifacts / provenance, and never imports or publishes
# anything. Runs as the NORMAL user — whole-script sudo is rejected; the
# one-time privileged perf setup is documented, out of band.
#
# --profile publication-core|publication-sweep|full|development|smoke
# ("publication" accepted as a deprecated alias for publication-core).
# --results-root <dir> (or PERFORMANCE_LAB_RESULTS_ROOT) — where evidence
# is written; the repository should hold only source and canonical
# imported evidence, not the raw/temporary measurement tree
# (docs/evidence-storage-retention.md section 9). --retain-raw-profiler-
# data forwards the same flag to every per-lab runner invocation (keeps
# perf.data/perf-c2c.data/JFR instead of the default delete-after-
# validated-summary policy) and excludes it from the default batch archive
# regardless (a separate raw archive is a maintainer's job if truly
# needed).
#
# Storage discipline (post the 2026-07 /home-filesystem-exhaustion
# incident): a filesystem preflight runs before any lab's build/test, a
# per-lab storage check runs before every lab in the sequence, and a final
# check runs before archive generation — any of them aborting the batch
# with failed-storage-preflight / failed-storage-budget /
# failed-artifact-size-limit, never "benchmark instability". Thresholds are
# configurable in the host config's `storage:` section (defaults: 80/40/30
# GiB minimum-before-batch/abort/max-batch-size, 2 GiB per-variant raw
# profiler cap, 200 MiB max text report, 2 GiB smoke budget).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck source=lib/evidence-lib.sh
source "${SCRIPT_DIR}/lib/evidence-lib.sh"
# shellcheck source=lib/storage-lib.sh
source "${SCRIPT_DIR}/lib/storage-lib.sh"
# Test hook: lets the batch tests substitute a controlled per-lab runner
# without duplicating orchestration logic in fixtures.
LAB_RUNNER="${PLAB_RUNNER_OVERRIDE:-${SCRIPT_DIR}/run-linux-evidence.sh}"
VERIFY_EVIDENCE="${PLAB_VERIFY_OVERRIDE:-${SCRIPT_DIR}/verify-evidence.sh}"

# Further test hooks (never needed in real use): fixture content/conf roots
# and a batch output root override.
CONTENT_ROOT="${PLAB_CONTENT_ROOT:-${REPO_ROOT}/content/labs}"
CONF_DIR="${PLAB_CONF_DIR:-${SCRIPT_DIR}/labs}"
RESULTS_ROOT="${PERFORMANCE_LAB_RESULTS_ROOT:-${REPO_ROOT}/results}"
BATCH_ROOT="${PLAB_BATCH_ROOT:-${RESULTS_ROOT}/batches}"

fail() { echo "run-all-benchmarks: $*" >&2; exit 1; }

if [ "$(id -u)" = "0" ]; then
  fail "do not run this script with sudo/as root — Java, Maven, Cargo, JMH, Criterion, harnesses and archives must run as/belong to the invoking user. One-time host setup for unprivileged perf (run separately): sudo sysctl kernel.perf_event_paranoid=-1 kernel.kptr_restrict=0"
fi

PROFILE="publication-core"
HOST_CONFIG=""
REPETITIONS=2
PREFLIGHT_ONLY=0
STABILITY_CHECK_ONLY=0
DRY_RUN=0
DIAGNOSTIC=0
RETAIN_RAW_PROFILER_DATA=0
while [ $# -gt 0 ]; do
  case "$1" in
    --profile) PROFILE="$2"; shift 2 ;;
    --host-config) HOST_CONFIG="$2"; shift 2 ;;
    --repetitions) REPETITIONS="$2"; shift 2 ;;
    --results-root) RESULTS_ROOT="$2"; BATCH_ROOT="${PLAB_BATCH_ROOT:-${RESULTS_ROOT}/batches}"; shift 2 ;;
    --preflight-only) PREFLIGHT_ONLY=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --stability-check-only) STABILITY_CHECK_ONLY=1; shift ;;
    --diagnostic) DIAGNOSTIC=1; shift ;;
    --retain-raw-profiler-data) RETAIN_RAW_PROFILER_DATA=1; shift ;;
    *) fail "unknown option: $1" ;;
  esac
done

# "publication" is a deprecated alias for "publication-core", normalized
# here so every downstream check (repetitions, dirty-tree gate,
# publicationEligible) uses one canonical set of profile names.
[ "$PROFILE" = "publication" ] && PROFILE="publication-core"
is_publication_profile() {
  case "$1" in
    publication-core|publication-sweep) return 0 ;;
    *) return 1 ;;
  esac
}

[ -n "$HOST_CONFIG" ] && [ -f "$HOST_CONFIG" ] || fail "--host-config <file> is required (template: config/benchmark-hosts/precision-5810.yaml)"
if is_publication_profile "$PROFILE" && [ "$REPETITIONS" -lt 2 ] && [ "$DIAGNOSTIC" != "1" ]; then
  fail "publication batches require --repetitions >= 2 (independent runs); pass --diagnostic for an explicitly non-publication single-run batch"
fi

# --- Constrained-YAML host config parser -----------------------------------
# Supports exactly the documented subset: flat "key: value" and one-level
# "section:" blocks with two-space-indented "key: value" lines.
cfg_flat() { # cfg_flat <key>
  awk -v key="$1" '/^[a-z_0-9]+:/ { split($0, kv, ": *"); if (kv[1] == key) { print kv[2]; exit } }' "$HOST_CONFIG" | tr -d '"'
}
cfg_section() { # cfg_section <section> <key>
  # Value normalization accepts the three natural ways to write a CPU set —
  # quoted CSV ("2,3"), bare CSV (2,3) and a YAML flow list ([2, 3]) — and
  # canonicalizes all of them to a space-free CSV.
  awk -v section="$1" -v key="$2" '
    /^[a-z_0-9]+:$/ { current = substr($0, 1, length($0) - 1); next }
    /^  [a-zA-Z0-9_-]+:/ {
      if (current == section) {
        line = $0; sub(/^  /, "", line); split(line, kv, ": *")
        if (kv[1] == key) {
          value = kv[2]
          gsub(/["\[\] ]/, "", value)
          print value; exit
        }
      }
    }' "$HOST_CONFIG"
}

[ "$(cfg_flat schema_version)" = "1" ] || fail "host config schema_version must be 1 (config/benchmark-hosts/schema.json)"
HOST_NAME="$(cfg_flat host_name)"
MAX_LOAD_X100="$(cfg_flat max_load_per_core_x100)"
STABILITY_TIMEOUT="$(cfg_flat stability_timeout_seconds)"
# Consecutive-sample stability policy (explicit in the host config; the
# 600s timeout is an upper bound, never a mandatory delay — a stable host
# passes after N samples × interval seconds).
STABILITY_CONSECUTIVE="$(cfg_flat stability_consecutive_samples)"
STABILITY_CONSECUTIVE="${STABILITY_CONSECUTIVE:-3}"
STABILITY_INTERVAL="${PLAB_STABILITY_POLL_SECONDS:-$(cfg_flat stability_sample_interval_seconds)}"
STABILITY_INTERVAL="${STABILITY_INTERVAL:-5}"
[ -n "$HOST_NAME" ] && [ -n "$MAX_LOAD_X100" ] && [ -n "$STABILITY_TIMEOUT" ] || fail "host config is missing host_name/max_load_per_core_x100/stability_timeout_seconds"

# --- Storage thresholds (docs/evidence-storage-retention.md) -----------------
# Configurable in the host config's `storage:` section (GiB/MiB for human
# readability there; exported as bytes for the shared storage-lib.sh
# functions and every per-lab runner invocation below). Every key is
# optional — an absent `storage:` section falls back to the documented
# defaults (80/40/30/2 GiB, 200 MiB), never to "unbounded".
storage_cfg_gib() { # <key> <default-gib>
  local v
  v="$(cfg_section storage "$1")"
  echo "${v:-$2}"
}
MIN_AVAILABLE_BEFORE_BATCH_GIB="$(storage_cfg_gib min_available_before_batch_gib 80)"
ABORT_THRESHOLD_GIB="$(storage_cfg_gib abort_threshold_during_batch_gib 40)"
MAX_BATCH_GIB="$(storage_cfg_gib max_total_batch_size_gib 30)"
MAX_RAW_PROFILER_GIB="$(storage_cfg_gib max_raw_profiler_bytes_per_variant_gib 2)"
MAX_TEXT_REPORT_MIB="$(storage_cfg_gib max_generated_text_report_size_mib 200)"
SMOKE_BUDGET_GIB="$(storage_cfg_gib smoke_budget_gib 2)"
# An already-set PLAB_* env var wins over the host-config-derived value
# (same precedence convention as PLAB_STABILITY_POLL_SECONDS above) — lets
# a focused rerun or a test override one threshold without editing the
# host config.
export PLAB_MIN_AVAILABLE_BEFORE_BATCH_BYTES="${PLAB_MIN_AVAILABLE_BEFORE_BATCH_BYTES:-$((MIN_AVAILABLE_BEFORE_BATCH_GIB * 1024 * 1024 * 1024))}"
export PLAB_ABORT_THRESHOLD_BYTES="${PLAB_ABORT_THRESHOLD_BYTES:-$((ABORT_THRESHOLD_GIB * 1024 * 1024 * 1024))}"
export PLAB_MAX_BATCH_BYTES="${PLAB_MAX_BATCH_BYTES:-$((MAX_BATCH_GIB * 1024 * 1024 * 1024))}"
export PLAB_MAX_RAW_PROFILER_BYTES_PER_VARIANT="${PLAB_MAX_RAW_PROFILER_BYTES_PER_VARIANT:-$((MAX_RAW_PROFILER_GIB * 1024 * 1024 * 1024))}"
export PLAB_MAX_TEXT_REPORT_BYTES="${PLAB_MAX_TEXT_REPORT_BYTES:-$((MAX_TEXT_REPORT_MIB * 1024 * 1024))}"
export PLAB_SMOKE_BUDGET_BYTES="${PLAB_SMOKE_BUDGET_BYTES:-$((SMOKE_BUDGET_GIB * 1024 * 1024 * 1024))}"
export PERFORMANCE_LAB_RESULTS_ROOT="$RESULTS_ROOT"

# Filesystem preflight (section 5): checked before ANY build or measurement
# — the 2026-07 incident's disk filled to zero headroom mid-batch; refusing
# to start when there is already insufficient headroom is cheap.
mkdir -p "$RESULTS_ROOT" 2>/dev/null || true
if [ "$DRY_RUN" != "1" ] && [ "$STABILITY_CHECK_ONLY" != "1" ]; then
  BATCH_FS_STATS="$(fs_stats_json "$RESULTS_ROOT")"
  BATCH_FS_AVAIL="$(printf '%s' "$BATCH_FS_STATS" | python3 -c 'import json,sys; print(json.load(sys.stdin)["availableBytes"])')"
  if [ "$BATCH_FS_AVAIL" -lt "$PLAB_MIN_AVAILABLE_BEFORE_BATCH_BYTES" ]; then
    echo "run-all-benchmarks: failed-storage-preflight — ${RESULTS_ROOT} has ${BATCH_FS_AVAIL} bytes available, below the ${PLAB_MIN_AVAILABLE_BEFORE_BATCH_BYTES}-byte minimum required before starting a batch:" >&2
    printf '%s\n' "$BATCH_FS_STATS" >&2
    fail "no measurements started (failed-storage-preflight)"
  fi
  echo "Storage preflight: ${RESULTS_ROOT} has $((BATCH_FS_AVAIL / 1024 / 1024 / 1024)) GiB available (minimum required: ${MIN_AVAILABLE_BEFORE_BATCH_GIB} GiB)"
fi

cooldown_for_class() {
  local seconds
  seconds="$(cfg_section cooldown_seconds "${1:-standard}")"
  echo "${seconds:-120}"
}

# --- Host state + stability ---------------------------------------------------
# JSON is generated STRUCTURALLY (capture_host_state_json in
# evidence-lib.sh, python3 json.dumps) and validated before the batch
# continues — the first real 5810 batch failed because a
# `pgrep -c || echo 0` double-printed "0\n0" into hand-concatenated JSON
# and the string compare in the stability loop then never matched.
# Malformed metrics are INFRASTRUCTURE errors (abort the whole batch),
# never "host instability".
write_host_state() { # <file>
  local file="$1"
  if ! capture_host_state_json > "$file"; then
    return 1
  fi
  validate_json_file "$file"
}

STABILITY_SAMPLES_FILE=""

# wait_for_stability <cooldown-seconds>
# returns: 0 = stable (N consecutive good samples)
#          1 = genuinely unstable until the timeout (blocked-unstable-host)
#          2 = infrastructure error (invalid metric — abort the batch)
# Every sample is appended, with all measured values, thresholds and
# explicit rejection reasons, to host-stability-samples.jsonl.
wait_for_stability() {
  local cooldown="$1"
  sleep "$cooldown"
  local waited=0
  local consecutive=0
  local nproc
  nproc="$(nproc 2>/dev/null || echo 1)"
  while :; do
    local procs sample
    if ! procs="$(count_benchmark_processes)"; then
      echo "run-all-benchmarks: invalid-stability-metric — the process counter itself failed; aborting (this is an infrastructure error, not host instability)" >&2
      return 2
    fi
    if sample="$(evaluate_stability_sample "${PLAB_PROC_LOADAVG:-/proc/loadavg}" "$nproc" "$MAX_LOAD_X100" "$procs")"; then
      consecutive=$((consecutive + 1))
    else
      # evaluate exits 1 for unstable, but a python failure yields no JSON:
      if [ -z "$sample" ]; then
        echo "run-all-benchmarks: invalid-stability-metric — sample evaluation failed; aborting" >&2
        return 2
      fi
      consecutive=0
    fi
    if [ -n "$STABILITY_SAMPLES_FILE" ]; then
      printf '%s\n' "$sample" >> "$STABILITY_SAMPLES_FILE"
    fi
    echo "   stability sample: ${sample}"
    if [ "$consecutive" -ge "$STABILITY_CONSECUTIVE" ]; then
      return 0
    fi
    # the timeout must advance even with a zero sample interval — the
    # upper bound is wall-clock-ish, never an infinite loop
    local step="$STABILITY_INTERVAL"
    [ "$step" -ge 1 ] || step=1
    waited=$((waited + step))
    if [ "$waited" -ge "$STABILITY_TIMEOUT" ]; then
      echo "run-all-benchmarks: host not stable within ${STABILITY_TIMEOUT}s — every rejected sample and its reasons are in host-stability-samples.jsonl" >&2
      return 1
    fi
    sleep "$STABILITY_INTERVAL"
  done
}

# --- Stability-only mode --------------------------------------------------------
# Captures/validates environment metadata and evaluates the consecutive-
# sample policy with every threshold printed — no inventory, no build, no
# JMH/harness/perf, no archive. Finishes in
# (consecutive × interval) seconds on an already-stable host.
if [ "$STABILITY_CHECK_ONLY" = "1" ]; then
  echo "Stability check (no benchmark, no profiler, no archive)"
  echo "  thresholds: maxLoadPerCore=$(LC_ALL=C awk -v m="$MAX_LOAD_X100" 'BEGIN{printf "%.2f", m/100}') consecutiveSamples=${STABILITY_CONSECUTIVE} sampleIntervalSeconds=${STABILITY_INTERVAL} timeoutSeconds=${STABILITY_TIMEOUT}"
  if ! HOST_STATE="$(capture_host_state_json)"; then
    echo "run-all-benchmarks: failed-environment-capture — see stderr above" >&2
    exit 3
  fi
  printf '%s
' "$HOST_STATE"
  wait_for_stability 0
  case $? in
    0) echo "Host stable (${STABILITY_CONSECUTIVE} consecutive good samples)."; exit 0 ;;
    1) echo "Host NOT stable within ${STABILITY_TIMEOUT}s — see the rejected samples above." >&2; exit 1 ;;
    *) echo "run-all-benchmarks: invalid-stability-metric — infrastructure error, not host instability." >&2; exit 3 ;;
  esac
fi

# --- Enabled-lab inventory ---------------------------------------------------
# Canonical sources only: content/labs/*/lab.json (benchmark: true) names
# the benchmark-capable labs; scripts/performance-lab/labs/<id>.conf is the
# evidence metadata. No second handwritten lab array.
BENCH_LABS=""
for lab_json in "${CONTENT_ROOT}"/*/lab.json; do
  dir="$(basename "$(dirname "$lab_json")")"
  case "$dir" in _*) continue ;; esac
  grep -q '"benchmark": *true' "$lab_json" && BENCH_LABS="${BENCH_LABS} ${dir}"
done

# Per-lab state lives in files (portable to bash 3.2 — macOS test hosts
# have no associative arrays).
STATE_DIR="$(mktemp -d)"
trap 'rm -rf "$STATE_DIR"' EXIT

# INT/TERM forwarding: the lab runner runs as a background child (below) so
# a signal reaching this orchestrator is forwarded as SIGTERM; the runner's
# own traps then terminate the benchmark process tree (SIGTERM first,
# SIGKILL only after the grace period — never the normal path).
CURRENT_LAB_PID=""
on_batch_signal() {
  echo "run-all-benchmarks: interrupted — forwarding SIGTERM to the running lab runner" >&2
  if [ -n "${CURRENT_LAB_PID}" ] && kill -0 "$CURRENT_LAB_PID" 2>/dev/null; then
    kill -TERM "$CURRENT_LAB_PID" 2>/dev/null || true
    wait "$CURRENT_LAB_PID" 2>/dev/null || true
  fi
  exit 130
}
trap on_batch_signal INT TERM
lab_set() { printf '%s' "$3" > "${STATE_DIR}/${1}.${2}"; }
lab_get() { cat "${STATE_DIR}/${1}.${2}" 2>/dev/null || true; }
lab_append_run() { # <lab> <json-entry>
  local file="${STATE_DIR}/${1}.runs"
  if [ -s "$file" ]; then printf ', %s' "$2" >> "$file"; else printf '%s' "$2" > "$file"; fi
}
ORDERED_LABS=""
BLOCKED=0
inspect_lab() {
  local lab="$1"
  local conf="${CONF_DIR}/${lab}.conf"
  if [ ! -f "$conf" ]; then
    lab_set "$lab" state BLOCKED; lab_set "$lab" reason "missing runner configuration"; return
  fi
  local meta
  if ! meta="$(bash -c "set -e; source '$conf'
    [ \"\${LAB_BENCHMARK_READY:-0}\" = 1 ] || { echo 'not marked benchmark-ready'; exit 3; }
    declare -f lab_variants lab_jmh_args lab_threads lab_cpu_count lab_worker_props lab_rust_evidence_cmd >/dev/null || { echo 'incomplete evidence configuration (missing required functions)'; exit 3; }
    [ -d '${REPO_ROOT}/'\"\$LAB_JAVA_DIR\" ] || { echo 'missing benchmark code ('\"\$LAB_JAVA_DIR\"')'; exit 3; }
    ls '${REPO_ROOT}/'\"\$LAB_JAVA_DIR\"/src/test/java/**/*.java >/dev/null 2>&1 || find '${REPO_ROOT}/'\"\$LAB_JAVA_DIR\"/src/test -name '*.java' 2>/dev/null | grep -q . || { echo 'missing correctness tests'; exit 3; }
    echo \"\${LAB_CPUS_EXACT:-\${LAB_MIN_CPUS:-2}} \${LAB_COOLDOWN_CLASS:-standard}\"" 2>&1)"; then
    lab_set "$lab" state BLOCKED; lab_set "$lab" reason "$meta"; return
  fi
  local min_cpus cooldown_class
  read -r min_cpus cooldown_class <<<"$meta"
  local set_name cpus
  set_name="$(cfg_section labs "$lab")"
  if [ -z "$set_name" ]; then
    lab_set "$lab" state BLOCKED; lab_set "$lab" reason "no cpu_set mapping in ${HOST_CONFIG}"; return
  fi
  cpus="$(cfg_section cpu_sets "$set_name")"
  if [ -z "$cpus" ]; then
    lab_set "$lab" state BLOCKED; lab_set "$lab" reason "cpu_set '${set_name}' is empty — fill it from lscpu -e=CPU,CORE,SOCKET,NODE,ONLINE"; return
  fi
  local count
  count="$(awk -F',' '{print NF}' <<<"$cpus")"
  if [ "$count" -ne "$min_cpus" ]; then
    lab_set "$lab" state BLOCKED; lab_set "$lab" reason "cardinality error: cpu_set '${set_name}' has ${count} CPU(s), lab requires exactly ${min_cpus}"; return
  fi
  lab_set "$lab" state READY; lab_set "$lab" cpuset "$cpus"; lab_set "$lab" cooldown "$cooldown_class"
}

for lab in $BENCH_LABS; do
  inspect_lab "$lab"
  ORDERED_LABS="${ORDERED_LABS} ${lab}"
  [ "$(lab_get "$lab" state)" = "READY" ] || BLOCKED=1
done

echo "Batch preflight (static inventory + host config)"
for lab in $ORDERED_LABS; do
  if [ "$(lab_get "$lab" state)" = "READY" ]; then
    printf '  %-20s READY (cpus %s)\n' "$lab" "$(lab_get "$lab" cpuset)"
  else
    printf '  %-20s BLOCKED: %s\n' "$lab" "$(lab_get "$lab" reason)"
  fi
done

BATCH_ID="batch-$(date -u +%Y%m%dT%H%M%SZ)"
BATCH_DIR="${BATCH_ROOT}/${BATCH_ID}"

if [ "$BLOCKED" = "1" ]; then
  echo
  echo "No measurements started."
  echo "run-all-benchmarks: one or more labs are blocked — the batch does not start until every enabled lab is READY (final state would be: failed-preflight)." >&2
  exit 1
fi

# --- Clean publication source (BEFORE any build/test) ---------------------------
# Ordering matters (batch-20260717 regression): live preflight runs Maven
# tests and benchmark builds, and those may create generated files — the
# source-state check must therefore run FIRST, and generated build outputs
# must be gitignored so they can never read as source drift. Publication
# provenance still requires: committed source, clean tracked tree before
# preflight, unchanged tracked tree after preflight, exact commit in the
# manifest. Ignored build products never make the source dirty.
SOURCE_COMMIT="$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
SOURCE_STATE_BEFORE="$(git -C "$REPO_ROOT" status --porcelain 2>/dev/null || true)"
SOURCE_SUBMODULES="$(git -C "$REPO_ROOT" submodule status 2>/dev/null || true)"
PUBLICATION_ELIGIBLE_BATCH="true"
if [ "$DRY_RUN" != "1" ]; then
  if [ -n "$SOURCE_STATE_BEFORE" ]; then
    if is_publication_profile "$PROFILE" || [ "$PROFILE" = "full" ]; then
      echo "run-all-benchmarks: dirty working tree — publication measurements require an exact, committed source state (checked BEFORE any build/test). Modified/untracked non-ignored paths:" >&2
      printf '%s\n' "$SOURCE_STATE_BEFORE" >&2
      exit 1
    fi
    PUBLICATION_ELIGIBLE_BATCH="false"
    echo "   dirty tree permitted for profile '${PROFILE}' — this batch is permanently publicationEligible=false"
  fi
fi
is_publication_profile "$PROFILE" || PUBLICATION_ELIGIBLE_BATCH="false"
echo "   source state: commit ${SOURCE_COMMIT} tracked-clean=$([ -z "$SOURCE_STATE_BEFORE" ] && echo yes || echo no) submodules=$([ -n "$SOURCE_SUBMODULES" ] && echo present || echo none)"

# --- Live preflight (runner-level, per lab) ----------------------------------
# The per-lab runner validates OS/virt/topology/perf/toolchain/correctness/
# permissions itself; this phase runs it for EVERY lab before any
# measurement. Aggregated report; any failure stops the batch.
echo
echo "Live preflight (per-lab runner, no measurement)"
LIVE_FAIL=0
PREFLIGHT_TMP="$(mktemp -d)"
for lab in $ORDERED_LABS; do
  if [ "$DRY_RUN" = "1" ]; then
    lab_set "$lab" preflight "SKIPPED (dry-run)"
    printf '  %-20s %s\n' "$lab" "$(lab_get "$lab" preflight)"
    continue
  fi
  if "$LAB_RUNNER" "$lab" --profile "$PROFILE" --cpus "$(lab_get "$lab" cpuset)" --preflight-only --out "$PREFLIGHT_TMP" \
      > "${PREFLIGHT_TMP}/${lab}.preflight.log" 2>&1; then
    lab_set "$lab" preflight "READY"
  else
    lab_set "$lab" preflight "BLOCKED: $(tail -1 "${PREFLIGHT_TMP}/${lab}.preflight.log" | head -c 200)"
    LIVE_FAIL=1
  fi
  printf '  %-20s %s\n' "$lab" "$(lab_get "$lab" preflight)"
done

write_batch_manifest() { # <state>
  # `lab` must be local: this function runs from inside the measurement
  # loop's abort paths, and clobbering the caller's ${lab} makes the abort
  # message name the wrong lab (last-in-order instead of the failing one).
  local state="$1" lab
  {
    echo "{"
    echo "  \"batchId\": \"${BATCH_ID}\","
    echo "  \"hostName\": \"${HOST_NAME}\","
    echo "  \"profile\": \"${PROFILE}\","
    echo "  \"repetitions\": ${REPETITIONS},"
    echo "  \"publicationEligible\": ${PUBLICATION_ELIGIBLE_BATCH:-false},"
    echo "  \"stabilityPolicy\": { \"consecutiveSamples\": ${STABILITY_CONSECUTIVE}, \"sampleIntervalSeconds\": ${STABILITY_INTERVAL}, \"timeoutSeconds\": ${STABILITY_TIMEOUT}, \"maxLoadPerCoreX100\": ${MAX_LOAD_X100} },"
    echo "  \"sourceCommit\": \"${SOURCE_COMMIT:-unknown}\","
    echo "  \"dirtyTree\": $([ -n "${SOURCE_STATE_BEFORE:-}" ] && echo true || echo false),"
    echo "  \"sourceStateBeforePreflight\": $([ -z "${SOURCE_STATE_BEFORE:-}" ] && echo '"clean"' || echo '"dirty (non-publication profile)"'),"
    echo "  \"submodules\": $([ -n "${SOURCE_SUBMODULES:-}" ] && echo '"present"' || echo '"none"'),"
    echo "  \"startedAt\": \"${BATCH_STARTED_AT:-}\","
    echo "  \"completedAt\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\","
    echo "  \"executionOrder\": [$(for lab in $ORDERED_LABS; do printf '"%s",' "$lab"; done | sed 's/,$//')],"
    echo "  \"labs\": {"
    local first=1
    for lab in $ORDERED_LABS; do
      [ "$first" = "1" ] || echo ","
      first=0
      printf '    "%s": { "cpus": "%s", "preflight": "%s", "runs": [%s] }' \
        "$lab" "$(lab_get "$lab" cpuset)" "$(lab_get "$lab" preflight)" "$(lab_get "$lab" runs)"
    done
    echo
    echo "  },"
    echo "  \"storage\": { \"resultsRoot\": \"${RESULTS_ROOT}\", \"sameFilesystemAsRepo\": ${SAME_FS_AS_REPO:-null}, \"thresholds\": { \"minAvailableBeforeBatchGib\": ${MIN_AVAILABLE_BEFORE_BATCH_GIB:-80}, \"abortThresholdGib\": ${ABORT_THRESHOLD_GIB:-40}, \"maxBatchGib\": ${MAX_BATCH_GIB:-30}, \"maxRawProfilerGibPerVariant\": ${MAX_RAW_PROFILER_GIB:-2}, \"smokeBudgetGib\": ${SMOKE_BUDGET_GIB:-2} }, \"rawProfilerRetained\": $([ "${RETAIN_RAW_PROFILER_DATA:-0}" = "1" ] && echo true || echo false) },"
    echo "  \"state\": \"${state}\""
    echo "}"
  } > "${BATCH_DIR}/batch-manifest.json"
}

# --- Post-preflight source integrity ---------------------------------------------
# Live preflight ran builds/tests: generated IGNORED files are expected and
# permitted, but a TRACKED file changing during preflight is an
# infrastructure defect of the preflight itself — classified as
# failed-preflight-source-mutation with the exact paths, never as user
# dirty-tree state.
if [ "$DRY_RUN" != "1" ]; then
  SOURCE_STATE_AFTER="$(git -C "$REPO_ROOT" status --porcelain 2>/dev/null || true)"
  if [ "$SOURCE_STATE_AFTER" != "$SOURCE_STATE_BEFORE" ]; then
    echo "run-all-benchmarks: failed-preflight-source-mutation — live preflight modified tracked/non-ignored files:" >&2
    diff <(printf '%s' "$SOURCE_STATE_BEFORE") <(printf '%s' "$SOURCE_STATE_AFTER") | grep '^[<>]' >&2 || true
    mkdir -p "$BATCH_DIR"
    write_batch_manifest "failed-preflight-source-mutation"
    exit 1
  fi
fi

if [ "$LIVE_FAIL" = "1" ]; then
  echo
  echo "No measurements started."
  mkdir -p "$BATCH_DIR"
  BATCH_STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  write_batch_manifest "failed-preflight"
  echo "run-all-benchmarks: live preflight failed — see ${BATCH_DIR}/batch-manifest.json" >&2
  exit 1
fi

if [ "$PREFLIGHT_ONLY" = "1" ]; then
  echo
  echo "Batch preflight passed for every enabled lab."
  echo "No measurements started (--preflight-only)."
  exit 0
fi

if [ "$DRY_RUN" = "1" ]; then
  echo
  echo "Planned sequence (dry-run — no benchmark, no profiler, no artifacts):"
  for rep in $(seq 1 "$REPETITIONS"); do
    for lab in $ORDERED_LABS; do
      echo "  [plan] cooldown $(cooldown_for_class "$(lab_get "$lab" cooldown)")s + stability wait (load <= ${MAX_LOAD_X100}%/core, timeout ${STABILITY_TIMEOUT}s)"
      echo "  [plan] ${LAB_RUNNER} ${lab} --profile ${PROFILE} --cpus $(lab_get "$lab" cpuset) --out results/batches/<batch-id>/run-${rep}"
      echo "  [plan] verify-evidence.sh <archive> ; record into batch manifest"
    done
  done
  echo "  [plan] batch manifest + SHA256SUMS + performance-lab-<batch-id>.tar.zst"
  exit 0
fi

# --- Sequential execution -------------------------------------------------------
mkdir -p "$BATCH_DIR/host-environment" "$BATCH_DIR/failed-runs"
# Provenance (section 9): if the results root and the repository sit on the
# same filesystem, say so explicitly in the batch manifest.
SAME_FS_AS_REPO="$(python3 -c "
import os
try:
    print('true' if os.stat('${RESULTS_ROOT}').st_dev == os.stat('${REPO_ROOT}').st_dev else 'false')
except OSError:
    print('null')
" 2>/dev/null || echo null)"
STABILITY_SAMPLES_FILE="${BATCH_DIR}/host-stability-samples.jsonl"
: > "$STABILITY_SAMPLES_FILE"
BATCH_STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
if ! write_host_state "${BATCH_DIR}/host-environment/before-batch.json"; then
  write_batch_manifest "failed-infrastructure"
  fail "failed-environment-capture: before-batch host state is invalid — aborting (infrastructure error, not host instability)"
fi
git -C "$REPO_ROOT" log -1 --format='{ "commit": "%H", "subject": "%s", "committedAt": "%cI" }' > "${BATCH_DIR}/source-revision.json" 2>/dev/null || echo '{}' > "${BATCH_DIR}/source-revision.json"
cp "$HOST_CONFIG" "${BATCH_DIR}/host-config.resolved.yaml"
{
  echo "{ \"hostConfig\": \"${HOST_CONFIG}\", \"labs\": {"
  first=1
  for lab in $ORDERED_LABS; do
    [ "$first" = "1" ] || printf ',\n'
    first=0
    printf '  "%s": "%s"' "$lab" "$(lab_get "$lab" cpuset)"
  done
  echo
  echo "} }"
} > "${BATCH_DIR}/host-config.resolved.json"

BATCH_STATE="complete"
for rep in $(seq 1 "$REPETITIONS"); do
  REP_DIR="${BATCH_DIR}/run-${rep}"
  mkdir -p "$REP_DIR"
  for lab in $ORDERED_LABS; do
    echo
    echo "== repetition ${rep}/${REPETITIONS}, lab ${lab} (sequential — nothing else measures)"
    STABILITY_RC=0
    wait_for_stability "$(cooldown_for_class "$(lab_get "$lab" cooldown)")" || STABILITY_RC=$?
    if [ "$STABILITY_RC" = "2" ]; then
      # An invariant/parser failure repeats identically for every lab and
      # repetition — abort the whole batch on first occurrence.
      lab_append_run "$lab" "{\"repetition\": ${rep}, \"status\": \"invalid-stability-metric\"}"
      write_batch_manifest "failed-infrastructure"
      fail "invalid-stability-metric on ${lab} — aborting the entire batch (infrastructure error, not host instability)"
    fi
    if [ "$STABILITY_RC" = "1" ]; then
      echo "run-all-benchmarks: host did not become stable within ${STABILITY_TIMEOUT}s — refusing to measure under uncontrolled load (rejected samples with reasons: host-stability-samples.jsonl)" >&2
      lab_append_run "$lab" "{\"repetition\": ${rep}, \"status\": \"blocked-unstable-host\"}"
      BATCH_STATE="partial"
      continue
    fi
    if ! PRE_STATE="$(capture_host_state_json)"; then
      lab_append_run "$lab" "{\"repetition\": ${rep}, \"status\": \"failed-environment-capture\"}"
      write_batch_manifest "failed-infrastructure"
      fail "failed-environment-capture on ${lab} — aborting the entire batch"
    fi
    # Storage check before every lab (section 6): a batch that just ran a
    # disk-hungry lab must not silently start another on an already-nearly-
    # full disk — this is the same abort threshold the runner itself
    # enforces per-variant, checked again here at the coarser per-lab grain.
    LAB_FS_AVAIL="$(fs_available_bytes "$RESULTS_ROOT")"
    if [ "$LAB_FS_AVAIL" -lt "$PLAB_ABORT_THRESHOLD_BYTES" ]; then
      lab_append_run "$lab" "{\"repetition\": ${rep}, \"status\": \"failed-storage-budget\"}"
      write_batch_manifest "failed-storage-budget"
      fail "failed-storage-budget — ${RESULTS_ROOT} has ${LAB_FS_AVAIL} bytes available (below the ${PLAB_ABORT_THRESHOLD_BYTES}-byte abort threshold), refusing to start ${lab} (repetition ${rep})"
    fi
    RETAIN_ARGS=()
    [ "$RETAIN_RAW_PROFILER_DATA" = "1" ] && RETAIN_ARGS=(--retain-raw-profiler-data)
    # The lab runner runs in the background so INT/TERM reaching this
    # orchestrator can be forwarded to it (its own traps then terminate the
    # benchmark process tree — SIGTERM first, SIGKILL only as last resort).
    # bash 3.2 (macOS's default /bin/bash) treats "${arr[@]}" on an EMPTY
    # array as unbound under `set -u` — the ${arr[@]+...} guard is the
    # portable idiom (evaluates to nothing when the array has no elements).
    RUNNER_RC=0
    "$LAB_RUNNER" "$lab" --profile "$PROFILE" --cpus "$(lab_get "$lab" cpuset)" --out "$REP_DIR" ${RETAIN_ARGS[@]+"${RETAIN_ARGS[@]}"} \
        > "${REP_DIR}/${lab}.console.log" 2>&1 &
    CURRENT_LAB_PID=$!
    wait "$CURRENT_LAB_PID" || RUNNER_RC=$?
    CURRENT_LAB_PID=""
    if [ "$RUNNER_RC" = "0" ]; then
      ARCHIVE="$(ls "${REP_DIR}/${lab}-"*"-linux-evidence.tar."* 2>/dev/null | head -1 || true)"
      if [ -n "$ARCHIVE" ] && "$VERIFY_EVIDENCE" "$ARCHIVE" > "${REP_DIR}/${lab}.verify.log" 2>&1; then
        HASH="$(shasum -a 256 "$ARCHIVE" 2>/dev/null || sha256sum "$ARCHIVE")"
        lab_append_run "$lab" "{\"repetition\": ${rep}, \"status\": \"collected\", \"archive\": \"${ARCHIVE#"$BATCH_DIR"/}\", \"sha256\": \"${HASH%% *}\", \"preHostState\": ${PRE_STATE}}"
      else
        lab_append_run "$lab" "{\"repetition\": ${rep}, \"status\": \"verification-failed\"}"
        BATCH_STATE="partial"
      fi
    elif [ "$RUNNER_RC" = "4" ]; then
      # Exit 4 = the runner's own storage-budget abort (a crossed abort
      # threshold or per-run byte budget mid-measurement). Treated exactly
      # like a timeout: the whole batch aborts rather than continuing on a
      # host that just ran a run out of disk headroom, and the partial
      # diagnostic manifest the runner wrote is preserved with the run.
      mkdir -p "${BATCH_DIR}/failed-runs/${lab}-run-${rep}"
      mv "${REP_DIR}/${lab}" "${BATCH_DIR}/failed-runs/${lab}-run-${rep}/" 2>/dev/null || true
      lab_append_run "$lab" "{\"repetition\": ${rep}, \"status\": \"failed-storage-budget\", \"console\": \"run-${rep}/${lab}.console.log\"}"
      write_batch_manifest "failed-storage-budget"
      fail "failed-storage-budget on ${lab} — the runner aborted after crossing a storage threshold mid-run; the partial diagnostic manifest is preserved (failed-runs/${lab}-run-${rep}); aborting the entire batch (never classified as benchmark instability)"
    elif [ "$RUNNER_RC" = "3" ]; then
      # Exit 3 = failed-benchmark-timeout: a benchmark exceeded its hard
      # wall-clock budget (a hang, like the SPSC producer spin that ran
      # 14h40m in batch-20260717T150131Z). The whole batch aborts — a host
      # that just carried a hung 100%-CPU JVM must not keep measuring as if
      # nothing happened, and the hang needs human attention, not skipping.
      mkdir -p "${BATCH_DIR}/failed-runs/${lab}-run-${rep}"
      mv "${REP_DIR}/${lab}" "${BATCH_DIR}/failed-runs/${lab}-run-${rep}/" 2>/dev/null || true
      lab_append_run "$lab" "{\"repetition\": ${rep}, \"status\": \"failed-benchmark-timeout\", \"console\": \"run-${rep}/${lab}.console.log\"}"
      write_batch_manifest "failed-benchmark-timeout"
      fail "failed-benchmark-timeout on ${lab} — a benchmark invocation exceeded its hard wall-clock budget; jcmd/thread-dump diagnostics are preserved with the failed run (failed-runs/${lab}-run-${rep}); aborting the entire batch (a hang is never silently skipped)"
    else
      # Methodological failures are never silently retried (retry budget: 0).
      mkdir -p "${BATCH_DIR}/failed-runs/${lab}-run-${rep}"
      mv "${REP_DIR}/${lab}" "${BATCH_DIR}/failed-runs/${lab}-run-${rep}/" 2>/dev/null || true
      lab_append_run "$lab" "{\"repetition\": ${rep}, \"status\": \"rejected-or-failed\", \"console\": \"run-${rep}/${lab}.console.log\"}"
      BATCH_STATE="partial"
    fi
  done
done

if ! write_host_state "${BATCH_DIR}/host-environment/after-batch.json"; then
  write_batch_manifest "failed-infrastructure"
  fail "failed-environment-capture: after-batch host state is invalid"
fi
write_batch_manifest "$BATCH_STATE"
write_sha256sums "$BATCH_DIR" || fail "batch hashing failed"

# Storage check + size sanity before archive generation (sections 6, 11):
# estimate the archive's input size and refuse to build an unreasonably
# large one rather than silently writing it and eating whatever disk is
# left. Evidence already on disk is NOT deleted by this check — only
# archive creation is skipped, so a maintainer can investigate first.
BATCH_BYTES_BEFORE_ARCHIVE="$(dir_size_bytes "$BATCH_DIR")"
if [ "$BATCH_BYTES_BEFORE_ARCHIVE" -gt "$PLAB_MAX_BATCH_BYTES" ]; then
  echo "run-all-benchmarks: failed-artifact-size-limit — batch content is ${BATCH_BYTES_BEFORE_ARCHIVE} bytes, exceeding the ${PLAB_MAX_BATCH_BYTES}-byte budget; refusing to build the archive. Evidence remains on disk at ${BATCH_DIR} for inspection." >&2
  write_batch_manifest "failed-artifact-size-limit"
  exit 1
fi
FS_AVAIL_BEFORE_ARCHIVE="$(fs_available_bytes "$BATCH_ROOT")"
if [ "$FS_AVAIL_BEFORE_ARCHIVE" -lt "$PLAB_ABORT_THRESHOLD_BYTES" ]; then
  echo "run-all-benchmarks: failed-storage-budget — only ${FS_AVAIL_BEFORE_ARCHIVE} bytes available before archive generation (below the ${PLAB_ABORT_THRESHOLD_BYTES}-byte abort threshold)." >&2
  write_batch_manifest "failed-storage-budget"
  exit 1
fi

# Built outside BATCH_DIR first (tar must never add an archive to itself),
# then moved inside so the final layout matches the documented structure.
# The default archive excludes raw-profiler artifacts (section 11) — this
# is belt-and-suspenders: by default no run has any (retire_raw_profiler_
# file already deleted them), and it only matters when a run was started
# with --retain-raw-profiler-data.
TAR_EXCLUDES=(--exclude='*perf.data' --exclude='*perf-c2c.data' --exclude='*.jfr')
if command -v zstd >/dev/null 2>&1; then
  TMP_ARCHIVE="${BATCH_ROOT}/.performance-lab-${BATCH_ID}.tar.zst.tmp"
  tar "${TAR_EXCLUDES[@]}" --zstd -cf "$TMP_ARCHIVE" -C "${BATCH_DIR}/.." "${BATCH_ID}"
  BATCH_ARCHIVE="${BATCH_DIR}/performance-lab-${BATCH_ID}.tar.zst"
else
  TMP_ARCHIVE="${BATCH_ROOT}/.performance-lab-${BATCH_ID}.tar.gz.tmp"
  tar "${TAR_EXCLUDES[@]}" -czf "$TMP_ARCHIVE" -C "${BATCH_DIR}/.." "${BATCH_ID}"
  BATCH_ARCHIVE="${BATCH_DIR}/performance-lab-${BATCH_ID}.tar.gz"
fi
mv "$TMP_ARCHIVE" "$BATCH_ARCHIVE"
if [ "$RETAIN_RAW_PROFILER_DATA" = "1" ] && find "$BATCH_DIR" \( -name 'perf.data' -o -name 'perf-c2c.data' \) 2>/dev/null | grep -q .; then
  echo "NOTE: raw profiler data was retained (--retain-raw-profiler-data) and is present on disk under ${BATCH_DIR}, but is excluded from the default archive above — archive it separately if you need to move it off-host."
fi

echo
echo "== BATCH $(echo "$BATCH_STATE" | tr '[:lower:]' '[:upper:]')"
echo "Batch manifest: ${BATCH_DIR}/batch-manifest.json"
echo "Batch archive:  ${BATCH_ARCHIVE}"
echo
echo "Next steps (on the repository machine — nothing is imported or published automatically):"
echo "  ./scripts/performance-lab/verify-benchmark-batch.sh ${BATCH_ARCHIVE}"
echo "  ./scripts/performance-lab/import-benchmark-batch.sh ${BATCH_ARCHIVE}"
echo
echo "Import derives at most measured/reproduced; reviewed/verified/published require human review (docs/measurement-environments.md)."
[ "$BATCH_STATE" = "complete" ] || exit 1
