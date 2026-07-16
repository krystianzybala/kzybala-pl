#!/usr/bin/env bash
# Shared, testable functions for the native-Linux evidence runner
# (scripts/performance-lab/run-linux-evidence.sh). Everything here is a
# pure function over arguments/files/environment variables so that
# scripts/test-linux-evidence.js can exercise topology validation, command
# construction and failure paths against fixtures — without PMU access and
# without fabricating a live perf run (the tests never pretend perf c2c
# executed; they only test what commands *would* run and how invalid input
# is rejected).

# --- Topology -------------------------------------------------------------
# topo_validate_cpus <lscpu-e-file> <cpuA> <cpuB> <allow_cross_socket:0|1>
#
# <lscpu-e-file> holds `lscpu -e=CPU,CORE,SOCKET,NODE,ONLINE` output.
# Validates the publication placement policy:
#   - both CPUs exist and are online,
#   - they are different logical CPUs,
#   - they map to different physical cores (which also excludes SMT
#     siblings — siblings share a CORE id),
#   - same socket and same NUMA node unless allow_cross_socket=1
#     (cross-socket/cross-node is a separate, explicitly requested scenario).
# On success prints "cpuA=<a> coreA=<ca> socketA=<sa> nodeA=<na> cpuB=..."
# on stdout; on failure prints the reason to stderr and returns 1.
topo_validate_cpus() {
  local file="$1" cpu_a="$2" cpu_b="$3" allow_cross="$4"

  if [ "$cpu_a" = "$cpu_b" ]; then
    echo "topology: --cpus must name two different logical CPUs (got ${cpu_a},${cpu_b})" >&2
    return 1
  fi

  local row_a row_b
  row_a=$(awk -v cpu="$cpu_a" 'NR>1 && $1==cpu {print; exit}' "$file")
  row_b=$(awk -v cpu="$cpu_b" 'NR>1 && $1==cpu {print; exit}' "$file")
  if [ -z "$row_a" ]; then echo "topology: CPU ${cpu_a} does not exist on this host" >&2; return 1; fi
  if [ -z "$row_b" ]; then echo "topology: CPU ${cpu_b} does not exist on this host" >&2; return 1; fi

  local core_a socket_a node_a online_a core_b socket_b node_b online_b
  read -r _ core_a socket_a node_a online_a <<<"$row_a"
  read -r _ core_b socket_b node_b online_b <<<"$row_b"

  if [ "$online_a" != "yes" ]; then echo "topology: CPU ${cpu_a} is offline" >&2; return 1; fi
  if [ "$online_b" != "yes" ]; then echo "topology: CPU ${cpu_b} is offline" >&2; return 1; fi

  if [ "$core_a" = "$core_b" ] && [ "$socket_a" = "$socket_b" ]; then
    echo "topology: CPUs ${cpu_a} and ${cpu_b} are SMT siblings of physical core ${core_a} (socket ${socket_a}) — false-sharing evidence requires two different physical cores" >&2
    return 1
  fi

  if [ "$socket_a" != "$socket_b" ] || [ "$node_a" != "$node_b" ]; then
    if [ "$allow_cross" != "1" ]; then
      echo "topology: CPUs ${cpu_a} (socket ${socket_a}, node ${node_a}) and ${cpu_b} (socket ${socket_b}, node ${node_b}) cross a socket/NUMA boundary — the primary false-sharing scenario requires same socket and node; pass --allow-cross-socket to run this as an explicit cross-socket scenario" >&2
      return 1
    fi
  fi

  echo "cpuA=${cpu_a} coreA=${core_a} socketA=${socket_a} nodeA=${node_a} cpuB=${cpu_b} coreB=${core_b} socketB=${socket_b} nodeB=${node_b}"
}

# topo_validate_single <lscpu-e-file> <cpu>
# Single-core labs: only existence and online state apply — pair topology
# (distinct physical cores, SMT siblings, socket/NUMA policy) is
# deliberately NOT validated because there is no second CPU to compare
# against. Prints "kind=single cpu=<c> core=<core> socket=<s> node=<n>".
topo_validate_single() {
  local file="$1" cpu="$2"
  local row
  row=$(awk -v cpu="$cpu" 'NR>1 && $1==cpu {print; exit}' "$file")
  if [ -z "$row" ]; then echo "topology: CPU ${cpu} does not exist on this host" >&2; return 1; fi
  local core socket node online
  read -r _ core socket node online <<<"$row"
  if [ "$online" != "yes" ]; then echo "topology: CPU ${cpu} is offline" >&2; return 1; fi
  echo "kind=single cpu=${cpu} core=${core} socket=${socket} node=${node}"
}

# topo_scenario <lscpu-e-file> <cpuA> <cpuB> — prints "same-socket" or
# "cross-socket" (assumes topo_validate_cpus already passed).
topo_scenario() {
  local file="$1" cpu_a="$2" cpu_b="$3"
  local socket_a socket_b node_a node_b
  socket_a=$(awk -v cpu="$cpu_a" 'NR>1 && $1==cpu {print $3; exit}' "$file")
  socket_b=$(awk -v cpu="$cpu_b" 'NR>1 && $1==cpu {print $3; exit}' "$file")
  node_a=$(awk -v cpu="$cpu_a" 'NR>1 && $1==cpu {print $4; exit}' "$file")
  node_b=$(awk -v cpu="$cpu_b" 'NR>1 && $1==cpu {print $4; exit}' "$file")
  if [ "$socket_a" != "$socket_b" ] || [ "$node_a" != "$node_b" ]; then
    echo "cross-socket"
  else
    echo "same-socket"
  fi
}

# --- Virtualization detection ----------------------------------------------
# detect_virtualization
#
# Exit status is the source of truth, never stdout text: on a physical host
# `systemd-detect-virt` prints "none" AND exits 1, and the literal value
# "none" must never be classified as virtualization. VM and container
# detection are queried separately (--vm / --container) with --quiet for
# the status check, then re-queried for the type label.
#
# Semantics:
#   physical host             -> prints nothing, returns 1
#   VM (kvm, vmware, ...)     -> prints "vm=<type> container=none", returns 0
#   container (docker, ...)   -> prints "vm=none container=<type>", returns 0
detect_virtualization() {
  local vm_type=""
  local container_type=""

  if command -v systemd-detect-virt >/dev/null 2>&1; then
    if systemd-detect-virt --vm --quiet 2>/dev/null; then
      vm_type="$(systemd-detect-virt --vm 2>/dev/null || true)"
    fi
    if systemd-detect-virt --container --quiet 2>/dev/null; then
      container_type="$(systemd-detect-virt --container 2>/dev/null || true)"
    fi
  elif [ -r /proc/cpuinfo ] && grep -q '^flags.*hypervisor' /proc/cpuinfo 2>/dev/null; then
    vm_type="hypervisor-flag"
  fi

  # A tool that exits 0 while printing "none" is still a physical host.
  [ "$vm_type" = "none" ] && vm_type=""
  [ "$container_type" = "none" ] && container_type=""

  if [ -n "$vm_type" ] || [ -n "$container_type" ]; then
    printf 'vm=%s container=%s\n' "${vm_type:-none}" "${container_type:-none}"
    return 0
  fi
  return 1
}

# --- Load check -----------------------------------------------------------
# check_load <loadavg-file> <nproc> <max-load-per-core-x100>
# Reads the 1-minute load average; fails when load/nproc exceeds the policy
# threshold (expressed ×100 to avoid floating point in bash: 20 = 0.20).
check_load() {
  local loadavg_file="$1" nproc="$2" max_x100="$3"
  local load1
  load1=$(awk '{print $1}' "$loadavg_file")
  # load × 100 / nproc, integer arithmetic
  local load_x100
  load_x100=$(awk -v l="$load1" -v n="$nproc" 'BEGIN { printf "%d", (l * 100) / n }')
  if [ "$load_x100" -gt "$max_x100" ]; then
    echo "load: 1-minute load average ${load1} on ${nproc} CPUs (${load_x100}% per core) exceeds the publication policy (${max_x100}%) — the host is under material background load" >&2
    return 1
  fi
  echo "load1=${load1} nproc=${nproc} perCoreX100=${load_x100}"
}

# --- Command construction ---------------------------------------------------
# All build_* functions only print the exact command line; they never
# execute anything. Parameters come from environment variables the runner
# resolves once (and tests set directly):
#   EV_JAR              path to benchmarks.jar
#   EV_SELECTOR         JMH benchmark selector regexp
#   EV_CPUS             explicit CPU list for this scenario ("A,B", or more)
#   EV_JVM_ARGS         resolved JVM args (heap, GC, worker pin properties)
#   EV_JMH_EXTRA        lab-config JMH parameter selection (e.g. "-p layout=shared")
#   EV_THREADS EV_FORKS EV_WI EV_W EV_I EV_R    resolved profile values
#   EV_PERF_EVENTS      comma-separated perf stat event list

build_taskset_prefix() {
  echo "taskset -c ${EV_CPUS}"
}

# The full-fork JMH timing run for one variant. Wrapped in a counting-mode
# perf stat for cpu-migrations/context-switches accounting (near-zero
# overhead; used to enforce the migration policy on the timing run itself).
build_jmh_evidence_command() {
  local variant="$1" outdir="$2"
  echo "perf stat -x, -e cpu-migrations,context-switches,task-clock -o ${outdir}/jmh-placement.csv -- $(build_taskset_prefix) java ${EV_JVM_ARGS} -jar ${EV_JAR} '${EV_SELECTOR}' ${EV_JMH_EXTRA} -t ${EV_THREADS} -f ${EV_FORKS} -wi ${EV_WI} -w ${EV_W} -i ${EV_I} -r ${EV_R} -rf json -rff ${outdir}/jmh.json"
}

# One perf stat counter repetition for one variant: single JMH fork inside,
# so the counters and the operation count come from the same execution.
# rep 1 writes perf-stat.csv (the canonical-layout name); reps 2..N write
# perf-stat-r<rep>.csv.
build_perf_stat_command() {
  local variant="$1" outdir="$2" rep="$3"
  local csv="${outdir}/perf-stat.csv"
  local jmh_json="${outdir}/perf-stat-jmh.json"
  if [ "$rep" -gt 1 ]; then
    csv="${outdir}/perf-stat-r${rep}.csv"
    jmh_json="${outdir}/perf-stat-jmh-r${rep}.json"
  fi
  echo "perf stat -x, -e ${EV_PERF_EVENTS} -o ${csv} -- $(build_taskset_prefix) java ${EV_JVM_ARGS} -jar ${EV_JAR} '${EV_SELECTOR}' ${EV_JMH_EXTRA} -t ${EV_THREADS} -f 1 -wi ${EV_WI} -w ${EV_W} -i ${EV_I} -r ${EV_R} -rf json -rff ${jmh_json}"
}

build_c2c_record_command() {
  local variant="$1" outdir="$2"
  echo "perf c2c record -o ${outdir}/perf-c2c.data -- $(build_taskset_prefix) java ${EV_JVM_ARGS} -jar ${EV_JAR} '${EV_SELECTOR}' ${EV_JMH_EXTRA} -t ${EV_THREADS} -f 1 -wi ${EV_WI} -w ${EV_W} -i ${EV_I} -r ${EV_R}"
}

build_c2c_report_command() {
  local variant="$1" outdir="$2"
  echo "perf c2c report -i ${outdir}/perf-c2c.data --stdio --show-all --call-graph none"
}

# --- Placement policy -------------------------------------------------------
# parse_perf_stat_event <csv-file> <event> — prints the counted value for
# one event from a `perf stat -x,` CSV, or "unavailable".
parse_perf_stat_event() {
  local csv="$1" event="$2"
  awk -F',' -v ev="$event" '$3==ev { print $1; found=1; exit } END { if (!found) print "unavailable" }' "$csv"
}

# check_migrations <csv-file> <max-per-second>
# Enforces the publication migration policy against the cpu-migrations and
# task-clock (ms) counters of a placement CSV.
check_migrations() {
  local csv="$1" max_per_sec="$2"
  local migrations task_clock_ms
  migrations=$(parse_perf_stat_event "$csv" "cpu-migrations")
  task_clock_ms=$(parse_perf_stat_event "$csv" "task-clock")
  if [ "$migrations" = "unavailable" ] || [ "$task_clock_ms" = "unavailable" ]; then
    echo "migration-policy: cpu-migrations or task-clock not counted — cannot enforce placement policy" >&2
    return 1
  fi
  local per_sec_x100
  per_sec_x100=$(awk -v m="$migrations" -v t="$task_clock_ms" 'BEGIN { if (t <= 0) { print "inf"; exit } printf "%d", (m * 100000) / t }')
  if [ "$per_sec_x100" = "inf" ] || [ "$per_sec_x100" -gt $((max_per_sec * 100)) ]; then
    echo "migration-policy: ${migrations} cpu-migrations over ${task_clock_ms} ms of task-clock exceeds the publication policy (${max_per_sec}/s) — run is invalid for publication" >&2
    return 1
  fi
  echo "migrations=${migrations} taskClockMs=${task_clock_ms}"
}

# --- Worker placement ---------------------------------------------------------
# merge_worker_placement <variant-dir>
#
# Merges the per-fork worker-placement-<pid>-<role>.json files the
# benchmark's pin states wrote into <variant-dir>/worker-placement.json and
# validates the BLOCKING worker policy: every worker pinned, observed on
# its intended CPU at teardown, zero migrations during the trial, and no
# two workers of one fork sharing a CPU. Prints a summary on success;
# prints the violations and returns 1 on failure. Requires python3 (present
# on any Ubuntu host this runner supports).
merge_worker_placement() {
  local vdir="$1"
  python3 - "$vdir" <<'PYEOF'
import glob, json, os, sys
vdir = sys.argv[1]
files = sorted(glob.glob(os.path.join(vdir, "worker-placement-*.json")))
if not files:
    print("worker-placement: no per-worker placement files found", file=sys.stderr)
    sys.exit(1)
entries, violations = [], []
for path in files:
    with open(path) as fh:
        entry = json.load(fh)
    entry["file"] = os.path.basename(path)
    entries.append(entry)
    name = f'{entry["file"]} ({entry.get("role")})'
    if entry.get("pinned") is not True:
        violations.append(f"{name}: not pinned")
    if entry.get("observedCpuAtTeardown") != entry.get("intendedCpu"):
        violations.append(f'{name}: intended CPU {entry.get("intendedCpu")} but at teardown on {entry.get("observedCpuAtTeardown")}')
    if entry.get("migrationsDuringTrial") != 0:
        violations.append(f'{name}: {entry.get("migrationsDuringTrial")} migration(s) during the measured trial')
# same-fork workers must not share a CPU (pid prefix groups a fork)
byfork = {}
for e in entries:
    pid = e["file"].split("-")[2]
    byfork.setdefault(pid, []).append(e)
for pid, group in byfork.items():
    cpus = [e.get("intendedCpu") for e in group]
    if len(set(cpus)) != len(cpus):
        violations.append(f"fork {pid}: two workers assigned the same CPU ({cpus})")
with open(os.path.join(vdir, "worker-placement.json"), "w") as fh:
    json.dump({"workers": entries, "violations": violations, "policy": "blocking"}, fh, indent=2)
    fh.write("\n")
if violations:
    print("worker-placement: BLOCKING violations:", file=sys.stderr)
    for v in violations:
        print(f"  - {v}", file=sys.stderr)
    sys.exit(1)
print(f"worker-placement: {len(entries)} worker record(s), all pinned, zero migrations")
PYEOF
}

# --- Host state and stability ---------------------------------------------------
# count_benchmark_processes
#
# Emits exactly ONE non-negative integer + newline. Linux pgrep -c prints
# "0" AND exits 1 when nothing matches — `pgrep -c ... || echo 0` therefore
# double-prints ("0\n0"), which is exactly the real-host bug this replaces:
# capture with `|| true`, default empty→0, validate, and SUM the category
# counts numerically (never concatenate command outputs). A malformed count
# is an infrastructure error (return 1), never host instability.
count_benchmark_processes() {
  local java_count rust_count runner_count category
  java_count="$(pgrep -fc 'benchmarks\.jar' 2>/dev/null || true)"
  rust_count="$(pgrep -fc 'spsc_evidence|cas_evidence|tpc_evidence|cache_evidence|aot_baseline' 2>/dev/null || true)"
  runner_count="$(pgrep -fc 'run-linux-evidence\.sh' 2>/dev/null || true)"
  java_count="${java_count:-0}"
  rust_count="${rust_count:-0}"
  runner_count="${runner_count:-0}"
  for category in "$java_count" "$rust_count" "$runner_count"; do
    if ! [[ "$category" =~ ^[0-9]+$ ]]; then
      echo "benchmark process counter produced invalid output: $(printf %q "$category")" >&2
      return 1
    fi
  done
  printf '%d\n' "$((java_count + rust_count + runner_count))"
}

# capture_host_state_json
#
# Structured JSON via python3 (never string concatenation) — every value
# passes through json.dumps, so a stray newline in an input can never
# corrupt the document. Returns 1 (infrastructure error) when the process
# counter is invalid. Load-average file is overridable for tests
# (PLAB_PROC_LOADAVG).
capture_host_state_json() {
  local procs
  procs="$(count_benchmark_processes)" || return 1
  local loadavg_file="${PLAB_PROC_LOADAVG:-/proc/loadavg}"
  local governor="unavailable" temp="unavailable" loadavg="unavailable"
  [ -r "$loadavg_file" ] && loadavg="$(cat "$loadavg_file")"
  [ -r /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor ] && governor="$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor)"
  if ls /sys/class/thermal/thermal_zone*/temp >/dev/null 2>&1; then
    temp="$(cat /sys/class/thermal/thermal_zone*/temp 2>/dev/null | sort -n | tail -1)"
  fi
  PLAB_J_CAPTURED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  PLAB_J_LOADAVG="$loadavg" \
  PLAB_J_GOVERNOR="$governor" \
  PLAB_J_THERMAL="$temp" \
  PLAB_J_MEM="$(awk '/MemAvailable/ {print $2}' /proc/meminfo 2>/dev/null || echo unavailable)" \
  PLAB_J_SWAP="$(awk '/SwapFree/ {print $2}' /proc/meminfo 2>/dev/null || echo unavailable)" \
  PLAB_J_PROCS="$procs" \
  python3 - <<'PYEOF'
import json, os
def maybe_int(value):
    try:
        return int(value)
    except (TypeError, ValueError):
        return value
print(json.dumps({
    "capturedAt": os.environ["PLAB_J_CAPTURED_AT"],
    "loadavg": os.environ["PLAB_J_LOADAVG"],
    "governorCpu0": os.environ["PLAB_J_GOVERNOR"],
    "maxThermalZone": maybe_int(os.environ["PLAB_J_THERMAL"]),
    "memAvailableKb": maybe_int(os.environ["PLAB_J_MEM"]),
    "swapFreeKb": maybe_int(os.environ["PLAB_J_SWAP"]),
    "benchmarkProcesses": int(os.environ["PLAB_J_PROCS"]),
}, indent=2))
PYEOF
}

# validate_json_file <file> — structural validation (jq -e equivalent);
# invalid metadata is an infrastructure error, never host instability.
validate_json_file() {
  python3 -m json.tool "$1" >/dev/null 2>&1
}

# evaluate_stability_sample <loadavg-file> <nproc> <max-load-per-core-x100> <benchmark-process-count>
#
# Prints ONE structured JSON line (for host-stability-samples.jsonl) with
# every measured value, every threshold, the verdict and — when rejected —
# every failing condition explicitly. Returns 0 stable / 1 unstable.
evaluate_stability_sample() {
  local loadavg_file="$1" nproc="$2" max_x100="$3" procs="$4"
  local load1="unavailable"
  [ -r "$loadavg_file" ] && load1="$(awk '{print $1}' "$loadavg_file")"
  local governor="unavailable" temp="unavailable"
  [ -r /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor ] && governor="$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor)"
  if ls /sys/class/thermal/thermal_zone*/temp >/dev/null 2>&1; then
    temp="$(cat /sys/class/thermal/thermal_zone*/temp 2>/dev/null | sort -n | tail -1)"
  fi
  PLAB_S_LOAD1="$load1" PLAB_S_NPROC="$nproc" PLAB_S_MAX="$max_x100" \
  PLAB_S_PROCS="$procs" PLAB_S_GOV="$governor" PLAB_S_TEMP="$temp" \
  python3 - <<'PYEOF'
import json, os, sys
reasons = []
nproc = int(os.environ["PLAB_S_NPROC"])
max_per_core = int(os.environ["PLAB_S_MAX"]) / 100.0
procs = int(os.environ["PLAB_S_PROCS"])
load_raw = os.environ["PLAB_S_LOAD1"]
load_per_core = None
if load_raw == "unavailable":
    # No loadavg source (non-Linux development host): the load condition
    # does not apply — the per-lab runner separately refuses to measure
    # anywhere but native Linux.
    load1 = None
else:
    load1 = float(load_raw)
    load_per_core = load1 / nproc
    if load_per_core > max_per_core:
        reasons.append(f"loadPerCore {load_per_core:.4f} > max {max_per_core:.2f}")
if procs != 0:
    reasons.append(f"benchmarkProcesses {procs} != 0 (another benchmark is running)")
stable = not reasons
print(json.dumps({
    "capturedAt": __import__("datetime").datetime.now(__import__("datetime").timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "load1": load1,
    "logicalCpus": nproc,
    "loadPerCore": round(load_per_core, 4) if load_per_core is not None else None,
    "maxLoadPerCore": max_per_core,
    "benchmarkProcesses": procs,
    "governor": os.environ["PLAB_S_GOV"],
    "maxThermalMilliC": os.environ["PLAB_S_TEMP"],
    "stable": stable,
    "reasons": reasons,
}))
sys.exit(0 if stable else 1)
PYEOF
}

# --- Hashing ----------------------------------------------------------------
# write_sha256sums <run-dir>
# Writes SHA256SUMS at the top of <run-dir> covering every regular file in
# it (except SHA256SUMS itself), with paths relative to <run-dir>.
write_sha256sums() {
  local dir="$1"
  local hasher
  if command -v sha256sum >/dev/null 2>&1; then
    hasher="sha256sum"
  elif command -v shasum >/dev/null 2>&1; then
    hasher="shasum -a 256"
  else
    echo "hashing: neither sha256sum nor shasum is available" >&2
    return 1
  fi
  (
    cd "$dir" || exit 1
    find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 $hasher > SHA256SUMS
  )
}
