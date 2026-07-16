#!/usr/bin/env bash
# Native-Linux publication-evidence runner for the Performance Lab
# (docs/linux-evidence-runner.md) — one runner, per-lab configuration.
#
#   ./scripts/performance-lab/run-linux-evidence.sh \
#     <lab-id> --profile publication --cpus <CPU_A>,<CPU_B>
#
# Run as the NORMAL user (never sudo); pick CPU ids from
# lscpu -e=CPU,CORE,SOCKET,NODE,ONLINE — distinct physical cores, no SMT
# siblings, same socket/NUMA node by default.
#
# Options: --profile publication|full|development|smoke, --cpus A,B
# (required), --allow-cross-socket, --preflight-only (run every preflight
# check, then stop before any measurement), --allow-virtualized
# (smoke|development wiring checks only — never publication|full),
# --out <dir>, --dry-run, --skip-load-check.
#
# Physical hosts are accepted: systemd-detect-virt exiting 1 (typically
# printing the literal "none") means no virtualization — detection is by
# exit status, never by stdout text (detect_virtualization in
# lib/evidence-lib.sh).
#
# Collects, per variant (shared / padded, each in its own JVM invocation,
# its own perf session and its own output directory):
#   - JMH -rf json evidence (multi-fork publication profile),
#   - perf stat counters (3 independent repetitions, CSV),
#   - perf c2c recording (binary perf-c2c.data preserved) + --stdio report,
# plus host topology, environment, toolchain, capability results,
# correctness-gate results, the resolved profile, a SHA-256 manifest over
# every artifact, and a single archive to copy off the host.
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

# --- Arguments ---------------------------------------------------------------
LAB_ID="${1:-}"; shift || true
PROFILE="publication"
CPUS=""
ALLOW_CROSS_SOCKET=0
ALLOW_VIRTUALIZED=0
PREFLIGHT_ONLY=0
DRY_RUN=0
SKIP_LOAD_CHECK=0
OUT_ROOT="${REPO_ROOT}/results"

while [ $# -gt 0 ]; do
  case "$1" in
    --profile) PROFILE="$2"; shift 2 ;;
    --cpus) CPUS="$2"; shift 2 ;;
    --allow-cross-socket) ALLOW_CROSS_SOCKET=1; shift ;;
    --allow-virtualized) ALLOW_VIRTUALIZED=1; shift ;;
    --preflight-only) PREFLIGHT_ONLY=1; shift ;;
    --out) OUT_ROOT="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --skip-load-check) SKIP_LOAD_CHECK=1; shift ;;
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
# A lab without a config file is rejected.
LAB_CONF="${SCRIPT_DIR}/labs/${LAB_ID:-none}.conf"
if [ -z "$LAB_ID" ] || [ ! -f "$LAB_CONF" ]; then
  SUPPORTED="$(ls "${SCRIPT_DIR}/labs" 2>/dev/null | sed 's/\.conf$//' | tr '\n' ' ')"
  fail "unsupported lab id '${LAB_ID:-<none>}' — supported: ${SUPPORTED:-none}"
fi
# shellcheck source=/dev/null
source "$LAB_CONF"

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

# --- Resolved publication profile -------------------------------------------
# Explicit values, stored verbatim in benchmark-profile.json. Multiple
# independent JVM forks are mandatory — one long invocation is not a
# substitute for fork-to-fork variance.
case "$PROFILE" in
  publication)
    EV_FORKS=5; EV_WI=5; EV_W="1s"; EV_I=10; EV_R="1s"
    ;;
  full)
    EV_FORKS=2; EV_WI=3; EV_W="1s"; EV_I=5; EV_R="1s"
    ;;
  development)
    EV_FORKS=1; EV_WI=2; EV_W="500ms"; EV_I=3; EV_R="500ms"
    ;;
  smoke)
    EV_FORKS=1; EV_WI=0; EV_W="200ms"; EV_I=1; EV_R="200ms"
    ;;
  *) fail "unsupported profile '${PROFILE}' (publication|full|development|smoke)" ;;
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
PERF_STAT_REPS=3
# Process-level aggregate migrations are host/JVM-noise evidence, recorded
# and bounded separately; benchmark-WORKER migrations (worker-placement
# evidence) are the blocking publication gate. The aggregate threshold only
# becomes blocking when worker pinning is unavailable.
MAX_AGGREGATE_MIGRATIONS_PER_SEC=50
MAX_LOAD_PER_CORE_X100=20
FAILURE_POLICY="abort-on-first-error"
export EV_SELECTOR EV_FORKS EV_WI EV_W EV_I EV_R EV_PERF_EVENTS

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
if VIRT_INFO="$(detect_virtualization)"; then
  VIRT_DETECTED="true"
  ENV_KIND="virtualized"
  PUBLICATION_ELIGIBLE="false"
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

# 8. Java/Maven toolchain present and actually runnable — `command -v`
#    alone is fooled by broken shims (e.g. macOS's /usr/bin/java stub with
#    no JDK); executing `java -version` is the real check, and it resolves
#    through whatever PATH the host uses (SDKMAN installs included).
if [ "$DRY_RUN" != "1" ]; then
  java -version >/dev/null 2>&1 || fail "java is not installed"
  command -v mvn >/dev/null 2>&1 || fail "mvn (Maven) is not installed"
fi

# 9. Correctness gate — measurement never starts on a failing suite.
echo "== correctness gate (mvn test)"
CORRECTNESS_STATUS="skipped-dry-run"
if [ "$DRY_RUN" != "1" ]; then
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
  "diffHash": ${GIT_DIFF_HASH}
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
# output permissions); nothing below (JMH, perf stat, perf c2c, archive)
# executes. Metadata files stay in place for inspection.
# =============================================================================
if [ "$PREFLIGHT_ONLY" = "1" ]; then
  echo
  echo "Preflight passed."
  echo "Host type: ${ENV_KIND}"
  if [ "$PUBLICATION_ELIGIBLE" = "true" ]; then
    echo "Publication profile eligible: yes"
  else
    echo "Publication profile eligible: no (virtualized — smoke/development wiring checks only)"
  fi
  echo "Selected CPUs: ${CPUS}"
  echo "Measurement was not started because --preflight-only was supplied."
  exit 0
fi

# =============================================================================
# Measurement — one variant at a time, fully separated. A policy violation
# stamps the run rejected (diagnostic only, never canonical evidence) and
# exits non-zero.
# =============================================================================
write_run_status() {
  cat > "${RUN_DIR}/run-status.json" <<STATEOF
{
  "runStatus": "$1",
  "publicationEligible": $([ "$1" = "collected" ] && echo "$PUBLICATION_ELIGIBLE" || echo "false"),
  "canonicalEvidenceEligible": $([ "$1" = "collected" ] && echo "true" || echo "false"),
  "rejectionReason": $([ -n "${2:-}" ] && json_escape "$2" || printf 'null')
}
STATEOF
}

mark_rejected() {
  echo "run-linux-evidence: RUN REJECTED — $1" >&2
  write_run_status "rejected" "$1"
  write_sha256sums "$RUN_DIR" >/dev/null 2>&1 || true
  echo "Partial artifacts preserved as DIAGNOSTIC ONLY in ${RUN_DIR} (run-status.json: rejected)." >&2
  exit 1
}

for VARIANT in $(lab_variants); do
  VDIR="${RUN_DIR}/${VARIANT}"
  mkdir -p "$VDIR"
  echo "== variant: ${VARIANT}"

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
  echo "   scenario kind=${VKIND} cpus=${SCEN_CPUS} threads=${VTHREADS} args='${EV_JMH_EXTRA}'"

  if [ "$VKIND" = "aux" ]; then
    AUX_CMD="$(lab_aux_evidence_cmd "$VARIANT")"
    [ -n "$AUX_CMD" ] || fail "variant ${VARIANT} is kind=aux but lab_aux_evidence_cmd returned nothing"
    # {VDIR} placeholder lets a config direct JVM logs (e.g. compilation
    # logs) into the variant's evidence directory.
    AUX_CMD="${AUX_CMD//\{VDIR\}/$VDIR}"
    AUX_DIR="${REPO_ROOT}/${LAB_AUX_DIR:-$LAB_JAVA_DIR}"
    echo "-- aux evidence harness"
    run_or_plan "(cd ${AUX_DIR} && perf stat -x, -e ${EV_PERF_EVENTS} -o ${VDIR}/jmh-placement.csv -- taskset -c ${SCEN_CPUS} ${AUX_CMD}) > ${VDIR}/aux-evidence.json 2> ${VDIR}/aux-evidence.log"
  else
    echo "-- JMH evidence run (${EV_FORKS} forks)"
    run_or_plan "$(build_jmh_evidence_command "$VARIANT" "$VDIR") > ${VDIR}/jmh-console.log 2>&1"

    echo "-- perf stat (${PERF_STAT_REPS} repetitions)"
    for REP in $(seq 1 "$PERF_STAT_REPS"); do
      run_or_plan "$(build_perf_stat_command "$VARIANT" "$VDIR" "$REP") > ${VDIR}/perf-stat-console-r${REP}.log 2>&1"
    done

    if [ "${LAB_C2C_REQUIRED:-0}" = "1" ] || [ "$PERF_C2C_OK" = "true" ]; then
      echo "-- perf c2c record + report"
      run_or_plan "$(build_c2c_record_command "$VARIANT" "$VDIR") > ${VDIR}/perf-c2c-console.log 2>&1"
      run_or_plan "$(build_c2c_report_command "$VARIANT" "$VDIR") > ${VDIR}/perf-c2c-report.txt 2> ${VDIR}/perf-c2c-report.log"
    else
      echo "-- perf c2c: optional for this lab and unavailable on this host — recorded as absent"
    fi
  fi

  RUST_CMD="$(lab_rust_evidence_cmd "$VARIANT")"
  if [ -n "$RUST_CMD" ]; then
    echo "-- Rust persistent-worker harness"
    run_or_plan "(cd ${RUST_DIR} && perf stat -x, -e ${EV_PERF_EVENTS} -o ${VDIR}/rust-perf-stat.csv -- taskset -c ${SCEN_CPUS} ${RUST_CMD}) > ${VDIR}/rust-evidence.json 2> ${VDIR}/rust-evidence.log"
  fi

  # --- Placement policy (after all of this variant's executions) -------------
  if [ "$DRY_RUN" != "1" ]; then
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
done

write_run_status "collected" ""

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
$(FIRST=1; for V in $(lab_variants); do
  [ "$FIRST" = "1" ] || printf ',\n'
  FIRST=0
  printf '    "%s": {\n' "$V"
  printf '      "jmh": "%s/jmh.json",\n' "$V"
  printf '      "jmhPlacement": "%s/jmh-placement.csv",\n' "$V"
  printf '      "workerPlacement": "%s/worker-placement.json",\n' "$V"
  printf '      "placementPolicy": "%s/placement-policy.json",\n' "$V"
  printf '      "perfStat": ["%s/perf-stat.csv", "%s/perf-stat-r2.csv", "%s/perf-stat-r3.csv"],\n' "$V" "$V" "$V"
  printf '      "perfStatJmh": ["%s/perf-stat-jmh.json", "%s/perf-stat-jmh-r2.json", "%s/perf-stat-jmh-r3.json"],\n' "$V" "$V" "$V"
  printf '      "perfC2cData": "%s/perf-c2c.data",\n' "$V"
  printf '      "perfC2cReport": "%s/perf-c2c-report.txt",\n' "$V"
  printf '      "rustEvidence": "%s/rust-evidence.json"\n' "$V"
  printf '    }'
done; printf '\n')
  },
  "runStatusFile": "run-status.json",
  "canonical": { "pendingImport": true, "importer": "scripts/performance-lab/import-evidence.sh (adds canonical-jmh.json, canonical-perf-stat.json, comparison.json on the repository machine)" },
  "review": { "verifiedMaturityRequiresHumanReview": true, "importDoesNotPromote": true }
}
MANEOF

if [ "$DRY_RUN" != "1" ]; then
  write_sha256sums "$RUN_DIR" || fail "hashing failed"
  ARCHIVE="${OUT_ROOT}/${LAB_ID}-${RUN_ID}-linux-evidence.tar"
  if command -v zstd >/dev/null 2>&1; then
    tar --zstd -cf "${ARCHIVE}.zst" -C "${OUT_ROOT}/${LAB_ID}" "${RUN_ID}"
    ARCHIVE="${ARCHIVE}.zst"
  else
    tar -czf "${ARCHIVE}.gz" -C "${OUT_ROOT}/${LAB_ID}" "${RUN_ID}"
    ARCHIVE="${ARCHIVE}.gz"
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
