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
#   EV_CPUS             "A,B" explicit CPU list
#   EV_JVM_ARGS         resolved JVM args (heap, GC)
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
  echo "perf stat -x, -e cpu-migrations,context-switches,task-clock -o ${outdir}/jmh-placement.csv -- $(build_taskset_prefix) java ${EV_JVM_ARGS} -jar ${EV_JAR} '${EV_SELECTOR}' -p layout=${variant} -t ${EV_THREADS} -f ${EV_FORKS} -wi ${EV_WI} -w ${EV_W} -i ${EV_I} -r ${EV_R} -rf json -rff ${outdir}/jmh.json"
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
  echo "perf stat -x, -e ${EV_PERF_EVENTS} -o ${csv} -- $(build_taskset_prefix) java ${EV_JVM_ARGS} -jar ${EV_JAR} '${EV_SELECTOR}' -p layout=${variant} -t ${EV_THREADS} -f 1 -wi ${EV_WI} -w ${EV_W} -i ${EV_I} -r ${EV_R} -rf json -rff ${jmh_json}"
}

build_c2c_record_command() {
  local variant="$1" outdir="$2"
  echo "perf c2c record -o ${outdir}/perf-c2c.data -- $(build_taskset_prefix) java ${EV_JVM_ARGS} -jar ${EV_JAR} '${EV_SELECTOR}' -p layout=${variant} -t ${EV_THREADS} -f 1 -wi ${EV_WI} -w ${EV_W} -i ${EV_I} -r ${EV_R}"
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
