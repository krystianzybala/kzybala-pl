#!/usr/bin/env bash
# Shared, testable storage/retention functions
# (docs/evidence-storage-retention.md) for run-linux-evidence.sh,
# run-all-benchmarks.sh, audit-evidence-storage.sh and cleanup-evidence.sh.
#
# Post batch-storage-exhaustion incident (2026-07): the /home filesystem on
# the 5810 filled completely (280/295 GB used) because raw perf.data /
# perf-c2c.data / JFR recordings and unbounded c2c text reports were kept
# for every variant and every repetition with no retention policy. Every
# function here is pure over its arguments/environment so
# scripts/test-evidence-storage.js can exercise budget/abort/cleanup logic
# against FAKE filesystem statistics — nothing here ever fills a real disk
# to test the "disk is full" path.

# --- Filesystem statistics ---------------------------------------------------
# fs_stats_json <path>
#
# Prints { filesystem, mountPoint, totalBytes, usedBytes, availableBytes,
# availablePercent } for the filesystem containing <path>, via Python's
# os.statvfs (f_bavail — blocks available to an UNPRIVILEGED user, which
# already excludes any ext4 reserved-block reserve; never total-minus-used,
# which would silently count blocks a normal user can never actually use).
#
# Test hook: PLAB_FAKE_FS_TOTAL_BYTES / PLAB_FAKE_FS_USED_BYTES /
# PLAB_FAKE_FS_AVAIL_BYTES short-circuit the real statvfs call so tests can
# simulate a nearly-full disk without creating one. PLAB_FAKE_FS_STATDEV
# (default 1) lets a test simulate two paths being the same/different
# filesystem without touching real device ids — two fs_stats_json calls
# with the same PLAB_FAKE_FS_STATDEV compare as "same filesystem". This
# flat form applies the SAME fake stats to every path checked, which is
# fine for single-filesystem scenarios but cannot simulate "two different
# paths, two different filesystems" (exactly the storage-target-vs-
# ephemeral-tmp bug class this file exists to regression-test) — for that,
# use PLAB_FAKE_FS_MAP: a JSON object mapping an exact path to its own
# {totalBytes, usedBytes, availableBytes, statDev, filesystem, mountPoint}
# (missing keys default sanely). PLAB_FAKE_FS_MAP is checked first; the
# flat PLAB_FAKE_FS_AVAIL_BYTES hook is checked next; real statvfs last.
fs_stats_json() {
  local path="$1"
  if [ -n "${PLAB_FAKE_FS_MAP:-}" ]; then
    PLAB_FS_PATH="$path" PLAB_FS_MAP="$PLAB_FAKE_FS_MAP" python3 - <<'PYEOF'
import json, os, sys
path = os.environ["PLAB_FS_PATH"]
fake_map = json.loads(os.environ["PLAB_FS_MAP"])
if path not in fake_map:
    print(f"fs_stats_json: PLAB_FAKE_FS_MAP has no entry for '{path}'", file=sys.stderr)
    sys.exit(1)
entry = fake_map[path]
total = int(entry.get("totalBytes", 0))
used = int(entry.get("usedBytes", 0))
avail = int(entry.get("availableBytes", 0))
pct = round((avail / total) * 100, 2) if total > 0 else 0.0
print(json.dumps({
    "path": path,
    "filesystem": entry.get("filesystem", "fake0"),
    "mountPoint": entry.get("mountPoint", "/fake"),
    "totalBytes": total,
    "usedBytes": used,
    "availableBytes": avail,
    "availablePercent": pct,
    "statDev": int(entry.get("statDev", 1)),
    "source": "fake (PLAB_FAKE_FS_MAP test hook)",
}))
PYEOF
    return $?
  fi
  if [ -n "${PLAB_FAKE_FS_AVAIL_BYTES:-}" ]; then
    PLAB_FS_PATH="$path" \
    PLAB_FS_TOTAL="${PLAB_FAKE_FS_TOTAL_BYTES:-0}" \
    PLAB_FS_USED="${PLAB_FAKE_FS_USED_BYTES:-0}" \
    PLAB_FS_AVAIL="${PLAB_FAKE_FS_AVAIL_BYTES}" \
    PLAB_FS_NAME="${PLAB_FAKE_FS_FILESYSTEM:-fake0}" \
    PLAB_FS_MOUNT="${PLAB_FAKE_FS_MOUNT:-/fake}" \
    PLAB_FS_STATDEV="${PLAB_FAKE_FS_STATDEV:-1}" \
    python3 - <<'PYEOF'
import json, os
total = int(os.environ["PLAB_FS_TOTAL"])
used = int(os.environ["PLAB_FS_USED"])
avail = int(os.environ["PLAB_FS_AVAIL"])
pct = round((avail / total) * 100, 2) if total > 0 else 0.0
print(json.dumps({
    "path": os.environ["PLAB_FS_PATH"],
    "filesystem": os.environ["PLAB_FS_NAME"],
    "mountPoint": os.environ["PLAB_FS_MOUNT"],
    "totalBytes": total,
    "usedBytes": used,
    "availableBytes": avail,
    "availablePercent": pct,
    "statDev": int(os.environ["PLAB_FS_STATDEV"]),
    "source": "fake (PLAB_FAKE_FS_* test hook)",
}))
PYEOF
    return $?
  fi
  PLAB_FS_PATH="$path" python3 - <<'PYEOF'
import json, os, subprocess, sys
path = os.environ["PLAB_FS_PATH"]
try:
    st = os.statvfs(path)
    stat_dev = os.stat(path).st_dev
except OSError as e:
    print(json.dumps({"error": str(e), "path": path}), file=sys.stderr)
    sys.exit(1)
total = st.f_frsize * st.f_blocks
avail = st.f_frsize * st.f_bavail
free_incl_reserved = st.f_frsize * st.f_bfree
used = total - free_incl_reserved
pct = round((avail / total) * 100, 2) if total > 0 else 0.0
filesystem, mount = "unavailable", "unavailable"
try:
    out = subprocess.run(["df", "-P", path], capture_output=True, text=True, timeout=5)
    lines = out.stdout.strip().splitlines()
    if len(lines) >= 2:
        cols = lines[-1].split()
        if len(cols) >= 6:
            filesystem = cols[0]
            mount = cols[5]
except Exception:
    pass
print(json.dumps({
    "path": path,
    "filesystem": filesystem,
    "mountPoint": mount,
    "totalBytes": total,
    "usedBytes": used,
    "availableBytes": avail,
    "availablePercent": pct,
    "statDev": stat_dev,
    "source": "statvfs (f_bavail — respects filesystem-reserved blocks)",
}))
PYEOF
}

# filesystem_relationship_json <path-a> <path-b>
#
# Compares the filesystems containing two paths by device id (os.stat's
# st_dev — the reliable identity check; device NAME strings from `df` can
# differ across bind mounts even on the same filesystem, and are shown here
# only for human readability, never used for the comparison itself). Prints
# { pathA: {filesystem, mountPoint, statDev}, pathB: {...}, same: bool }.
# `same` is a JSON boolean, never null, as long as both paths resolve —
# resolution failure on either path is a hard error (non-zero return),
# never a silent null.
filesystem_relationship_json() {
  local path_a="$1" path_b="$2"
  local stats_a stats_b
  stats_a="$(fs_stats_json "$path_a")" || { echo "filesystem_relationship_json: cannot resolve filesystem for '${path_a}'" >&2; return 1; }
  stats_b="$(fs_stats_json "$path_b")" || { echo "filesystem_relationship_json: cannot resolve filesystem for '${path_b}'" >&2; return 1; }
  python3 - "$stats_a" "$stats_b" <<'PYEOF'
import json, sys
a = json.loads(sys.argv[1])
b = json.loads(sys.argv[2])
print(json.dumps({
    "pathA": {"path": a["path"], "filesystem": a["filesystem"], "mountPoint": a["mountPoint"], "device": a["filesystem"], "statDev": a["statDev"]},
    "pathB": {"path": b["path"], "filesystem": b["filesystem"], "mountPoint": b["mountPoint"], "device": b["filesystem"], "statDev": b["statDev"]},
    "same": a["statDev"] == b["statDev"],
}, indent=2))
PYEOF
}

# fs_available_bytes <path> — convenience: just the availableBytes integer.
fs_available_bytes() {
  fs_stats_json "$1" | python3 -c 'import json,sys; print(json.load(sys.stdin)["availableBytes"])'
}

# --- Storage-target validation --------------------------------------------------
# validate_storage_target <path>
#
# The storage TARGET (docs/evidence-storage-retention.md — "the filesystem
# that will hold the real evidence") is a distinct concept from the
# temporary --out directory a preflight-only invocation writes small
# diagnostic files to (batch-storage-target-bug incident, 2026-07: the
# batch's per-lab live preflight passed a throwaway mktemp -d under /tmp as
# --out, and the runner's capacity check evaluated THAT ephemeral
# directory instead of the real results root, blocking every lab even
# though the real target had ample space).
#
# Validates, in order: non-empty path; exists or has a creatable parent
# (mkdir -p); is writable (create+remove a probe file — catches a
# read-only remount that mkdir -p's own success wouldn't); resolves to a
# real filesystem with valid (>0) byte counts. Prints the resolved absolute
# path on success; prints the specific failing reason to stderr and
# returns 1 on ANY failure — never silently substitutes a different
# directory.
validate_storage_target() {
  local target="$1"
  if [ -z "$target" ]; then
    echo "storage-target: empty path" >&2
    return 1
  fi
  if ! mkdir -p "$target" 2>/dev/null; then
    echo "storage-target: cannot create '${target}' (or a parent directory) — check permissions" >&2
    return 1
  fi
  local resolved
  resolved="$(cd "$target" 2>/dev/null && pwd)" || {
    echo "storage-target: cannot resolve '${target}' to an absolute path" >&2
    return 1
  }
  local probe="${resolved}/.storage-target-write-probe-$$"
  if ! ( : > "$probe" ) 2>/dev/null; then
    echo "storage-target: '${resolved}' is not writable" >&2
    return 1
  fi
  rm -f "$probe"
  local stats total
  if ! stats="$(fs_stats_json "$resolved")"; then
    echo "storage-target: cannot determine filesystem statistics for '${resolved}'" >&2
    return 1
  fi
  total="$(printf '%s' "$stats" | python3 -c 'import json,sys; print(json.load(sys.stdin)["totalBytes"])' 2>/dev/null)"
  if [ -z "$total" ] || [ "$total" -le 0 ]; then
    echo "storage-target: '${resolved}' reports invalid filesystem byte counts (totalBytes=${total:-unknown})" >&2
    return 1
  fi
  printf '%s' "$resolved"
}

# storage_diagnostics_json <reason-code> <checked-path> <mount-point> <available-bytes> <required-bytes> <message>
#
# Structured, UNTRUNCATED storage-failure diagnostics (docs/evidence-
# storage-retention.md section 6) — printed for embedding into
# run-status.json's storageDiagnostics field and, from there, into the
# batch orchestrator's per-lab manifest entry. Replaces the old pattern of
# clipping a human-readable reason string into a plain JSON string field.
storage_diagnostics_json() {
  local reason_code="$1" checked_path="$2" mount_point="$3" available_bytes="$4" required_bytes="$5" message="$6"
  python3 - "$reason_code" "$checked_path" "$mount_point" "$available_bytes" "$required_bytes" "$message" <<'PYEOF'
import json, sys
reason_code, checked_path, mount_point, available_bytes, required_bytes, message = sys.argv[1:7]
def maybe_int(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return None
print(json.dumps({
    "reasonCode": reason_code,
    "checkedPath": checked_path,
    "mountPoint": mount_point,
    "availableBytes": maybe_int(available_bytes),
    "requiredBytes": maybe_int(required_bytes),
    "message": message,
}, indent=2))
PYEOF
}

# --- Directory sizing ---------------------------------------------------------
# dir_size_bytes <path> — total apparent size (sum of file sizes) in bytes,
# 0 if the path does not exist. Implemented via os.walk (not `du`, whose
# block-size/apparent-size semantics differ between GNU and BSD) so the
# number is identical on the Linux measurement host and a macOS dev check.
dir_size_bytes() {
  local path="$1"
  PLAB_DS_PATH="$path" python3 - <<'PYEOF'
import os
path = os.environ["PLAB_DS_PATH"]
total = 0
if os.path.isdir(path):
    for root, _dirs, files in os.walk(path):
        for name in files:
            fp = os.path.join(root, name)
            try:
                total += os.lstat(fp).st_size
            except OSError:
                pass
elif os.path.isfile(path):
    total = os.lstat(path).st_size
print(total)
PYEOF
}

# file_size_bytes <path> — 0 if missing.
file_size_bytes() {
  local path="$1"
  if [ -f "$path" ]; then
    python3 -c "import os,sys; print(os.lstat(sys.argv[1]).st_size)" "$path"
  else
    echo 0
  fi
}

# --- Storage preflight / budget checks -----------------------------------------
# storage_preflight_check <path> <min-available-bytes>
# Prints a JSON verdict and returns 0 (ok) / 1 (insufficient space).
storage_preflight_check() {
  local path="$1" min_bytes="$2"
  local stats avail
  stats="$(fs_stats_json "$path")" || { echo "$stats" >&2; return 1; }
  avail="$(printf '%s' "$stats" | python3 -c 'import json,sys; print(json.load(sys.stdin)["availableBytes"])')"
  local ok=1
  [ "$avail" -ge "$min_bytes" ] && ok=0
  python3 - "$stats" "$min_bytes" "$ok" <<'PYEOF'
import json, sys
stats = json.loads(sys.argv[1])
min_bytes = int(sys.argv[2])
ok = sys.argv[3] == "0"
out = dict(stats)
out["minimumRequiredBytes"] = min_bytes
out["withinBudget"] = ok
out["check"] = "storage-preflight"
print(json.dumps(out, indent=2))
PYEOF
  return "$ok"
}

# storage_budget_check <label> <current-bytes> <max-bytes>
# Generic "is current usage within budget" check used for smoke budgets,
# max-batch-bytes, max-raw-profiler-bytes-per-variant and max-text-report
# thresholds alike. Prints JSON, returns 0 within / 1 exceeded.
storage_budget_check() {
  local label="$1" current="$2" max="$3"
  local ok=1
  [ "$current" -le "$max" ] && ok=0
  python3 - "$label" "$current" "$max" "$ok" <<'PYEOF'
import json, sys
label, current, max_bytes, ok = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4] == "0"
print(json.dumps({
    "check": label,
    "currentBytes": current,
    "maxBytes": max_bytes,
    "withinBudget": ok,
}, indent=2))
PYEOF
  return "$ok"
}

# --- Defaults (docs/evidence-storage-retention.md "Storage thresholds") -------
# Every default is overridable via environment variable (set by
# run-all-benchmarks.sh from the host config's `storage:` section, or
# directly by a maintainer running run-linux-evidence.sh standalone).
storage_default_min_available_before_batch_bytes() { echo "${PLAB_MIN_AVAILABLE_BEFORE_BATCH_BYTES:-$((80 * 1024 * 1024 * 1024))}"; }
storage_default_abort_threshold_bytes() { echo "${PLAB_ABORT_THRESHOLD_BYTES:-$((40 * 1024 * 1024 * 1024))}"; }
storage_default_max_batch_bytes() { echo "${PLAB_MAX_BATCH_BYTES:-$((30 * 1024 * 1024 * 1024))}"; }
storage_default_max_raw_profiler_bytes_per_variant() { echo "${PLAB_MAX_RAW_PROFILER_BYTES_PER_VARIANT:-$((2 * 1024 * 1024 * 1024))}"; }
storage_default_max_text_report_bytes() { echo "${PLAB_MAX_TEXT_REPORT_BYTES:-$((200 * 1024 * 1024))}"; }
storage_default_smoke_budget_bytes() { echo "${PLAB_SMOKE_BUDGET_BYTES:-$((2 * 1024 * 1024 * 1024))}"; }

# --- Raw-profiler lifecycle -----------------------------------------------------
# hash_file_sha256 <path> — prints the hex digest, "unavailable" if the file
# is missing or no hasher is present.
hash_file_sha256() {
  local path="$1"
  [ -f "$path" ] || { echo "unavailable"; return 0; }
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$path" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$path" | awk '{print $1}'
  else
    echo "unavailable"
  fi
}

# retire_raw_profiler_file <raw-file> <summary-report-file> <retain:0|1> <out-json>
#
# Section 7 (raw-profiler cleanup): the raw file is hashed and sized BEFORE
# any deletion decision, the retention record is written unconditionally
# (so even a retained file has recorded provenance), and only THEN — unless
# retain=1 — is the raw file removed. Never deletes before the summary
# report exists and is non-empty (report validation is the caller's job;
# this function refuses to run if the report is missing/empty, since a
# report that doesn't exist yet means the report generation itself is not
# known-good).
retire_raw_profiler_file() {
  local raw="$1" report="$2" retain="$3" out_json="$4"
  if [ ! -s "$report" ]; then
    echo "retire_raw_profiler_file: refusing — summary report '${report}' is missing or empty (raw data is never deleted before a validated summary exists)" >&2
    return 1
  fi
  local original_bytes sha
  original_bytes="$(file_size_bytes "$raw")"
  sha="$(hash_file_sha256 "$raw")"
  local retained_bool="false"
  [ "$retain" = "1" ] && retained_bool="true"
  python3 - "$raw" "$report" "$original_bytes" "$sha" "$retained_bool" "$out_json" <<'PYEOF'
import json, sys
raw, report, original_bytes, sha, retained, out_json = sys.argv[1:7]
with open(out_json, "w") as fh:
    json.dump({
        "rawProfilerFile": raw,
        "summaryReport": report,
        "rawProfilerOriginalBytes": int(original_bytes),
        "rawProfilerSha256": sha,
        "rawProfilerRetained": retained == "true",
    }, fh, indent=2)
    fh.write("\n")
PYEOF
  if [ "$retain" != "1" ] && [ -f "$raw" ]; then
    rm -f "$raw"
  fi
}

# --- Bounded text reports --------------------------------------------------------
# enforce_text_size_limit <file> <max-bytes>
# A generated text report (e.g. a summarized perf-c2c report) must never
# exceed the configured hard cap — this is a defensive rejection for a
# pathological report, not the target size (the summarizer itself already
# bounds normal output to a handful of KB). Returns 1 (failed-artifact-
# size-limit) and leaves the oversized file in place for inspection.
enforce_text_size_limit() {
  local file="$1" max_bytes="$2"
  local size
  size="$(file_size_bytes "$file")"
  if [ "$size" -gt "$max_bytes" ]; then
    echo "enforce_text_size_limit: ${file} is ${size} bytes, exceeding the ${max_bytes}-byte cap (failed-artifact-size-limit)" >&2
    return 1
  fi
  return 0
}

# --- Storage progress logging ---------------------------------------------------
# append_storage_progress <jsonl-file> <batch-bytes> <variant-bytes> <raw-profiler-bytes> <fs-available-bytes> <within-budget:0|1>
append_storage_progress() {
  local file="$1" batch_bytes="$2" variant_bytes="$3" raw_bytes="$4" fs_avail="$5" within="$6"
  local within_bool="false"
  [ "$within" = "1" ] && within_bool="true"
  python3 - "$batch_bytes" "$variant_bytes" "$raw_bytes" "$fs_avail" "$within_bool" <<'PYEOF' >> "$file"
import json, sys, datetime
batch_bytes, variant_bytes, raw_bytes, fs_avail, within = sys.argv[1:6]
print(json.dumps({
    "capturedAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "batchBytes": int(batch_bytes),
    "variantBytes": int(variant_bytes),
    "rawProfilerBytes": int(raw_bytes),
    "filesystemAvailableBytes": int(fs_avail),
    "withinBudget": within == "true",
}))
PYEOF
}

# --- Classification passthrough (single source of truth: evidence_classify.py) -
# classify_path <repo-root> <relative-path>
STORAGE_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
classify_path() {
  local relpath="$1"
  PYTHONPATH="${STORAGE_LIB_DIR}${PYTHONPATH:+:${PYTHONPATH}}" python3 -c '
import sys
from evidence_classify import classify
print(classify(sys.argv[1]))
' "$relpath"
}
