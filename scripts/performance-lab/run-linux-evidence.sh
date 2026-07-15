#!/usr/bin/env bash
# Native-Linux publication-evidence runner for the false-sharing reference
# lab (docs/linux-evidence-runner.md).
#
#   sudo ./scripts/performance-lab/run-linux-evidence.sh \
#     false-sharing --profile publication --cpus 2,4
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
DRY_RUN=0
SKIP_LOAD_CHECK=0
OUT_ROOT="${REPO_ROOT}/results"

while [ $# -gt 0 ]; do
  case "$1" in
    --profile) PROFILE="$2"; shift 2 ;;
    --cpus) CPUS="$2"; shift 2 ;;
    --allow-cross-socket) ALLOW_CROSS_SOCKET=1; shift ;;
    --out) OUT_ROOT="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --skip-load-check) SKIP_LOAD_CHECK=1; shift ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

fail() { echo "run-linux-evidence: $*" >&2; exit 1; }

[ "$LAB_ID" = "false-sharing" ] || fail "only the false-sharing lab is supported (got '${LAB_ID:-<none>}')"
[ -n "$CPUS" ] || fail "--cpus A,B is required: publication runs never silently choose CPUs"
case "$CPUS" in
  *,*) : ;;
  *) fail "--cpus must name exactly two logical CPUs, e.g. --cpus 2,4" ;;
esac
CPU_A="${CPUS%%,*}"
CPU_B="${CPUS##*,}"

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
  *) fail "unsupported profile '${PROFILE}' (publication|full)" ;;
esac
EV_THREADS=2
EV_JVM_ARGS="-Xms1g -Xmx1g -XX:+UseParallelGC"
EV_SELECTOR="pl.kzybala.lab.falsesharing.FalseSharingLinuxEvidenceBenchmark"
EV_PERF_EVENTS="cycles,instructions,cache-references,cache-misses,branches,branch-misses,task-clock,context-switches,cpu-migrations,page-faults"
PERF_STAT_REPS=3
MAX_MIGRATIONS_PER_SEC=5
MAX_LOAD_PER_CORE_X100=20
FAILURE_POLICY="abort-on-first-error"
export EV_CPUS="$CPUS" EV_JVM_ARGS EV_SELECTOR EV_THREADS EV_FORKS EV_WI EV_W EV_I EV_R EV_PERF_EVENTS

JAVA_DIR="${REPO_ROOT}/content/labs/false-sharing/code/java"
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

# 2. Not emulated / virtualized.
VIRT="none"
if command -v systemd-detect-virt >/dev/null 2>&1; then
  VIRT="$(systemd-detect-virt 2>/dev/null || echo none)"
elif [ -r /proc/cpuinfo ] && grep -q '^flags.*hypervisor' /proc/cpuinfo 2>/dev/null; then
  VIRT="hypervisor-flag"
fi
if [ "$VIRT" != "none" ] && [ "$DRY_RUN" != "1" ]; then
  fail "virtualization/emulation detected ('${VIRT}') — VMs, containers on foreign architectures and emulators are never publication evidence"
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
  [ "$PERF_STAT_OK" = "true" ] || fail "perf stat cannot count events (perf_event_paranoid=${PERF_PARANOID}; run under sudo or lower the sysctl)"
  [ "$PERF_C2C_OK" = "true" ] || fail "perf c2c cannot open its required memory events on this CPU/kernel — not every x86_64 part supports useful c2c evidence; this host cannot produce the HITM evidence this lab requires"
fi

# 4. Topology.
LSCPU_E_FILE="$(mktemp)"
if command -v lscpu >/dev/null 2>&1; then
  lscpu -e=CPU,CORE,SOCKET,NODE,ONLINE > "$LSCPU_E_FILE" 2>/dev/null || fail "lscpu -e failed"
else
  [ "$DRY_RUN" = "1" ] || fail "lscpu is unavailable"
fi
if [ -s "$LSCPU_E_FILE" ]; then
  TOPO_RESULT="$(topo_validate_cpus "$LSCPU_E_FILE" "$CPU_A" "$CPU_B" "$ALLOW_CROSS_SOCKET")" || fail "CPU selection rejected"
  SCENARIO="$(topo_scenario "$LSCPU_E_FILE" "$CPU_A" "$CPU_B")"
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

# 8. Correctness gate — measurement never starts on a failing suite.
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
  echo "== build benchmarks.jar"
  (cd "$JAVA_DIR" && mvn -q -DskipTests package) >> "${RUN_DIR}/correctness-console.log" 2>&1 \
    || fail "mvn package failed"
  [ -f "$EV_JAR" ] || fail "benchmarks.jar missing after build"
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

cat > "${RUN_DIR}/environment.json" <<ENVEOF
{
  "runner": "run-linux-evidence.sh",
  "labId": "${LAB_ID}",
  "runId": "${RUN_ID}",
  "capturedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "kernel": $(json_escape "$(capture_or_unavailable uname -a)"),
  "virtualization": $(json_escape "$VIRT"),
  "selectedCpus": { "cpuA": "${CPU_A}", "cpuB": "${CPU_B}", "validation": $(json_escape "$TOPO_RESULT"), "scenario": $(json_escape "$SCENARIO") },
  "governorCpuA": $(json_escape "$(governor_for "$CPU_A")"),
  "governorCpuB": $(json_escape "$(governor_for "$CPU_B")"),
  "freqMinCpuA": $(json_escape "$(freq_for "$CPU_A" scaling_min_freq)"),
  "freqMaxCpuA": $(json_escape "$(freq_for "$CPU_A" scaling_max_freq)"),
  "freqCurCpuA": $(json_escape "$(freq_for "$CPU_A" scaling_cur_freq)"),
  "freqMinCpuB": $(json_escape "$(freq_for "$CPU_B" scaling_min_freq)"),
  "freqMaxCpuB": $(json_escape "$(freq_for "$CPU_B" scaling_max_freq)"),
  "freqCurCpuB": $(json_escape "$(freq_for "$CPU_B" scaling_cur_freq)"),
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
  "jvmArgs": $(json_escape "$EV_JVM_ARGS"),
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
  "virtualization": $(json_escape "$VIRT"),
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
    "threads": ${EV_THREADS},
    "selector": $(json_escape "$EV_SELECTOR")
  },
  "jvm": { "args": $(json_escape "$EV_JVM_ARGS") },
  "placement": { "cpus": "${CPUS}", "mechanism": "taskset -c (confines every JVM thread, including GC/JIT, to the selected CPUs; it cannot pin thread A to CPU A specifically — migrations between the two allowed CPUs are counted and bounded by policy instead)", "maxCpuMigrationsPerSecond": ${MAX_MIGRATIONS_PER_SEC}, "scenario": $(json_escape "$SCENARIO") },
  "perfStatRepetitions": ${PERF_STAT_REPS},
  "failurePolicy": "${FAILURE_POLICY}",
  "loadPolicy": { "maxOneMinuteLoadPerCoreX100": ${MAX_LOAD_PER_CORE_X100} }
}
PROFEOF

cat > "${RUN_DIR}/correctness.json" <<COREOF
{
  "gate": "mvn test (CounterCorrectnessTest, CounterLayoutTest, EvidenceBenchmarkContractTest)",
  "status": "${CORRECTNESS_STATUS}",
  "console": "correctness-console.log"
}
COREOF

# =============================================================================
# Measurement — one variant at a time, fully separated.
# =============================================================================
for VARIANT in shared padded; do
  VDIR="${RUN_DIR}/${VARIANT}"
  mkdir -p "$VDIR"
  echo "== variant: ${VARIANT}"

  echo "-- JMH evidence run (${EV_FORKS} forks)"
  run_or_plan "$(build_jmh_evidence_command "$VARIANT" "$VDIR") > ${VDIR}/jmh-console.log 2>&1"
  if [ "$DRY_RUN" != "1" ]; then
    check_migrations "${VDIR}/jmh-placement.csv" "$MAX_MIGRATIONS_PER_SEC" \
      || fail "variant ${VARIANT}: JMH timing run violated the CPU-migration policy"
  fi

  echo "-- perf stat (${PERF_STAT_REPS} repetitions)"
  for REP in $(seq 1 "$PERF_STAT_REPS"); do
    run_or_plan "$(build_perf_stat_command "$VARIANT" "$VDIR" "$REP") > ${VDIR}/perf-stat-console-r${REP}.log 2>&1"
  done

  echo "-- perf c2c record + report"
  run_or_plan "$(build_c2c_record_command "$VARIANT" "$VDIR") > ${VDIR}/perf-c2c-console.log 2>&1"
  run_or_plan "$(build_c2c_report_command "$VARIANT" "$VDIR") > ${VDIR}/perf-c2c-report.txt 2> ${VDIR}/perf-c2c-report.log"
done

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
    "shared": {
      "jmh": "shared/jmh.json",
      "jmhPlacement": "shared/jmh-placement.csv",
      "perfStat": ["shared/perf-stat.csv", "shared/perf-stat-r2.csv", "shared/perf-stat-r3.csv"],
      "perfStatJmh": ["shared/perf-stat-jmh.json", "shared/perf-stat-jmh-r2.json", "shared/perf-stat-jmh-r3.json"],
      "perfC2cData": "shared/perf-c2c.data",
      "perfC2cReport": "shared/perf-c2c-report.txt"
    },
    "padded": {
      "jmh": "padded/jmh.json",
      "jmhPlacement": "padded/jmh-placement.csv",
      "perfStat": ["padded/perf-stat.csv", "padded/perf-stat-r2.csv", "padded/perf-stat-r3.csv"],
      "perfStatJmh": ["padded/perf-stat-jmh.json", "padded/perf-stat-jmh-r2.json", "padded/perf-stat-jmh-r3.json"],
      "perfC2cData": "padded/perf-c2c.data",
      "perfC2cReport": "padded/perf-c2c-report.txt"
    }
  },
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
