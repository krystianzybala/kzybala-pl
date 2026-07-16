#!/usr/bin/env bash
# Canonical batch orchestrator: executes every benchmark-ready Performance
# Lab sequentially on the dedicated native-Linux host and produces one
# complete evidence package for later site-wide import.
#
#   ./scripts/performance-lab/run-all-benchmarks.sh \
#     --profile publication \
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
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck source=lib/evidence-lib.sh
source "${SCRIPT_DIR}/lib/evidence-lib.sh"
# Test hook: lets the batch tests substitute a controlled per-lab runner
# without duplicating orchestration logic in fixtures.
LAB_RUNNER="${PLAB_RUNNER_OVERRIDE:-${SCRIPT_DIR}/run-linux-evidence.sh}"
VERIFY_EVIDENCE="${PLAB_VERIFY_OVERRIDE:-${SCRIPT_DIR}/verify-evidence.sh}"
STABILITY_POLL="${PLAB_STABILITY_POLL_SECONDS:-10}"
# Further test hooks (never needed in real use): fixture content/conf roots
# and a batch output root override.
CONTENT_ROOT="${PLAB_CONTENT_ROOT:-${REPO_ROOT}/content/labs}"
CONF_DIR="${PLAB_CONF_DIR:-${SCRIPT_DIR}/labs}"
BATCH_ROOT="${PLAB_BATCH_ROOT:-${REPO_ROOT}/results/batches}"

fail() { echo "run-all-benchmarks: $*" >&2; exit 1; }

if [ "$(id -u)" = "0" ]; then
  fail "do not run this script with sudo/as root — Java, Maven, Cargo, JMH, Criterion, harnesses and archives must run as/belong to the invoking user. One-time host setup for unprivileged perf (run separately): sudo sysctl kernel.perf_event_paranoid=-1 kernel.kptr_restrict=0"
fi

PROFILE="publication"
HOST_CONFIG=""
REPETITIONS=2
PREFLIGHT_ONLY=0
DRY_RUN=0
DIAGNOSTIC=0
while [ $# -gt 0 ]; do
  case "$1" in
    --profile) PROFILE="$2"; shift 2 ;;
    --host-config) HOST_CONFIG="$2"; shift 2 ;;
    --repetitions) REPETITIONS="$2"; shift 2 ;;
    --preflight-only) PREFLIGHT_ONLY=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --diagnostic) DIAGNOSTIC=1; shift ;;
    *) fail "unknown option: $1" ;;
  esac
done

[ -n "$HOST_CONFIG" ] && [ -f "$HOST_CONFIG" ] || fail "--host-config <file> is required (template: config/benchmark-hosts/precision-5810.yaml)"
if [ "$PROFILE" = "publication" ] && [ "$REPETITIONS" -lt 2 ] && [ "$DIAGNOSTIC" != "1" ]; then
  fail "publication batches require --repetitions >= 2 (independent runs); pass --diagnostic for an explicitly non-publication single-run batch"
fi

# --- Constrained-YAML host config parser -----------------------------------
# Supports exactly the documented subset: flat "key: value" and one-level
# "section:" blocks with two-space-indented "key: value" lines.
cfg_flat() { # cfg_flat <key>
  awk -v key="$1" '/^[a-z_0-9]+:/ { split($0, kv, ": *"); if (kv[1] == key) { print kv[2]; exit } }' "$HOST_CONFIG" | tr -d '"'
}
cfg_section() { # cfg_section <section> <key>
  awk -v section="$1" -v key="$2" '
    /^[a-z_0-9]+:$/ { current = substr($0, 1, length($0) - 1); next }
    /^  [a-zA-Z0-9_-]+:/ {
      if (current == section) {
        line = $0; sub(/^  /, "", line); split(line, kv, ": *")
        if (kv[1] == key) { gsub(/"/, "", kv[2]); print kv[2]; exit }
      }
    }' "$HOST_CONFIG"
}

[ "$(cfg_flat schema_version)" = "1" ] || fail "host config schema_version must be 1 (config/benchmark-hosts/schema.json)"
HOST_NAME="$(cfg_flat host_name)"
MAX_LOAD_X100="$(cfg_flat max_load_per_core_x100)"
STABILITY_TIMEOUT="$(cfg_flat stability_timeout_seconds)"
[ -n "$HOST_NAME" ] && [ -n "$MAX_LOAD_X100" ] && [ -n "$STABILITY_TIMEOUT" ] || fail "host config is missing host_name/max_load_per_core_x100/stability_timeout_seconds"

cooldown_for_class() {
  local seconds
  seconds="$(cfg_section cooldown_seconds "${1:-standard}")"
  echo "${seconds:-120}"
}

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
    echo \"\${LAB_MIN_CPUS:-2} \${LAB_COOLDOWN_CLASS:-standard}\"" 2>&1)"; then
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
  if [ "$count" -lt "$min_cpus" ]; then
    lab_set "$lab" state BLOCKED; lab_set "$lab" reason "cpu_set '${set_name}' has ${count} CPU(s), lab needs >= ${min_cpus}"; return
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
  local state="$1"
  {
    echo "{"
    echo "  \"batchId\": \"${BATCH_ID}\","
    echo "  \"hostName\": \"${HOST_NAME}\","
    echo "  \"profile\": \"${PROFILE}\","
    echo "  \"repetitions\": ${REPETITIONS},"
    echo "  \"sourceCommit\": \"$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)\","
    echo "  \"dirtyTree\": $([ -n "$(git -C "$REPO_ROOT" status --porcelain 2>/dev/null)" ] && echo true || echo false),"
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
    echo "  \"state\": \"${state}\""
    echo "}"
  } > "${BATCH_DIR}/batch-manifest.json"
}

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

# --- Host state + stability ---------------------------------------------------
host_state_json() {
  local governor="unavailable" temp="unavailable"
  [ -r /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor ] && governor="$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor)"
  if ls /sys/class/thermal/thermal_zone*/temp >/dev/null 2>&1; then
    temp="$(cat /sys/class/thermal/thermal_zone*/temp 2>/dev/null | sort -n | tail -1)"
  fi
  printf '{ "capturedAt": "%s", "loadavg": "%s", "governorCpu0": "%s", "maxThermalZone": "%s", "memAvailableKb": "%s", "swapFreeKb": "%s", "benchmarkProcesses": %s }' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    "$(cat /proc/loadavg 2>/dev/null || echo unavailable)" \
    "$governor" "$temp" \
    "$(awk '/MemAvailable/ {print $2}' /proc/meminfo 2>/dev/null || echo unavailable)" \
    "$(awk '/SwapFree/ {print $2}' /proc/meminfo 2>/dev/null || echo unavailable)" \
    "$(pgrep -fc 'benchmarks.jar|spsc_evidence|cas_evidence' 2>/dev/null || echo 0)"
}

wait_for_stability() { # <cooldown-seconds>
  local cooldown="$1"
  sleep "$cooldown"
  local waited=0
  local nproc
  nproc="$(nproc 2>/dev/null || echo 1)"
  while :; do
    local procs load_ok
    procs="$(pgrep -fc 'benchmarks.jar|spsc_evidence|cas_evidence' 2>/dev/null || echo 0)"
    load_ok=1
    # Load/memory checks apply on the real (Linux) host; the per-lab runner
    # separately refuses to measure anywhere else.
    if [ -r /proc/loadavg ]; then
      check_load /proc/loadavg "$nproc" "$MAX_LOAD_X100" >/dev/null 2>&1 || load_ok=0
    fi
    if [ "$procs" = "0" ] && [ "$load_ok" = "1" ]; then
      return 0
    fi
    waited=$((waited + STABILITY_POLL))
    if [ "$waited" -ge "$STABILITY_TIMEOUT" ]; then
      return 1
    fi
    sleep "$STABILITY_POLL"
  done
}

# --- Sequential execution -------------------------------------------------------
mkdir -p "$BATCH_DIR/host-environment" "$BATCH_DIR/failed-runs"
BATCH_STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
host_state_json > "${BATCH_DIR}/host-environment/before-batch.json"
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
    if ! wait_for_stability "$(cooldown_for_class "$(lab_get "$lab" cooldown)")"; then
      echo "run-all-benchmarks: host did not become stable within ${STABILITY_TIMEOUT}s — refusing to measure under uncontrolled load" >&2
      lab_append_run "$lab" "{\"repetition\": ${rep}, \"status\": \"blocked-unstable-host\"}"
      BATCH_STATE="partial"
      continue
    fi
    PRE_STATE="$(host_state_json)"
    if "$LAB_RUNNER" "$lab" --profile "$PROFILE" --cpus "$(lab_get "$lab" cpuset)" --out "$REP_DIR" \
        > "${REP_DIR}/${lab}.console.log" 2>&1; then
      ARCHIVE="$(ls "${REP_DIR}/${lab}-"*"-linux-evidence.tar."* 2>/dev/null | head -1 || true)"
      if [ -n "$ARCHIVE" ] && "$VERIFY_EVIDENCE" "$ARCHIVE" > "${REP_DIR}/${lab}.verify.log" 2>&1; then
        HASH="$(shasum -a 256 "$ARCHIVE" 2>/dev/null || sha256sum "$ARCHIVE")"
        lab_append_run "$lab" "{\"repetition\": ${rep}, \"status\": \"collected\", \"archive\": \"${ARCHIVE#"$BATCH_DIR"/}\", \"sha256\": \"${HASH%% *}\", \"preHostState\": ${PRE_STATE}}"
      else
        lab_append_run "$lab" "{\"repetition\": ${rep}, \"status\": \"verification-failed\"}"
        BATCH_STATE="partial"
      fi
    else
      # Methodological failures are never silently retried (retry budget: 0).
      mkdir -p "${BATCH_DIR}/failed-runs/${lab}-run-${rep}"
      mv "${REP_DIR}/${lab}" "${BATCH_DIR}/failed-runs/${lab}-run-${rep}/" 2>/dev/null || true
      lab_append_run "$lab" "{\"repetition\": ${rep}, \"status\": \"rejected-or-failed\", \"console\": \"run-${rep}/${lab}.console.log\"}"
      BATCH_STATE="partial"
    fi
  done
done

host_state_json > "${BATCH_DIR}/host-environment/after-batch.json"
write_batch_manifest "$BATCH_STATE"
write_sha256sums "$BATCH_DIR" || fail "batch hashing failed"
# Built outside BATCH_DIR first (tar must never add an archive to itself),
# then moved inside so the final layout matches the documented structure.
if command -v zstd >/dev/null 2>&1; then
  TMP_ARCHIVE="${BATCH_ROOT}/.performance-lab-${BATCH_ID}.tar.zst.tmp"
  tar --zstd -cf "$TMP_ARCHIVE" -C "${BATCH_DIR}/.." "${BATCH_ID}"
  BATCH_ARCHIVE="${BATCH_DIR}/performance-lab-${BATCH_ID}.tar.zst"
else
  TMP_ARCHIVE="${BATCH_ROOT}/.performance-lab-${BATCH_ID}.tar.gz.tmp"
  tar -czf "$TMP_ARCHIVE" -C "${BATCH_DIR}/.." "${BATCH_ID}"
  BATCH_ARCHIVE="${BATCH_DIR}/performance-lab-${BATCH_ID}.tar.gz"
fi
mv "$TMP_ARCHIVE" "$BATCH_ARCHIVE"

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
