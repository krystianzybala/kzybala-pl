#!/usr/bin/env bash
# Deterministic repair/reindex of an EXISTING batch's manifests, indexes and
# hashes — never its measurements. Use this to recover a batch whose
# per-run evidence-manifest.json files were produced by a buggy generator
# (e.g. the unconditional-path-declaration defect that produced 18
# verification-failed runs in batch-20260727T121031Z even though the
# underlying JMH/perf-stat/perf-c2c/Rust/placement artifacts were fine):
# this tool re-derives each run's manifest from the component model driven
# by (a) the lab's actual resolved execution policy for that variant and
# (b) the artifacts genuinely present on disk, then re-verifies with the
# CURRENT verify-evidence.sh. It never re-executes JMH, perf stat, perf
# c2c, the Rust harness or the placement/migration checks, and it never
# fabricates an artifact that does not exist — a component the lab would
# have scheduled but whose file is genuinely missing stays "failed".
#
#   ./scripts/performance-lab/reindex-evidence.sh \
#     --batch-dir results/batches/batch-20260727T121031Z \
#     --output-dir results/repaired/batch-20260727T121031Z-r1 \
#     --dry-run
#
# --batch-dir must be an EXTRACTED batch directory (containing
# batch-manifest.json and run-*/ subdirectories) — never the .tar archive
# itself and never modified. --output-dir must not already exist; the
# repaired batch (new derived batch id, new archive, full transformation
# log) is written there. --dry-run reports the plan (which runs would be
# reindexed, from what state to what) without writing anything.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/evidence-lib.sh
source "${SCRIPT_DIR}/lib/evidence-lib.sh"
# shellcheck source=lib/storage-lib.sh
source "${SCRIPT_DIR}/lib/storage-lib.sh"

fail() { echo "reindex-evidence: $*" >&2; exit 1; }

BATCH_DIR=""
OUTPUT_DIR=""
DRY_RUN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --batch-dir) BATCH_DIR="${2:-}"; shift 2 ;;
    --output-dir) OUTPUT_DIR="${2:-}"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    *) fail "unknown argument: $1" ;;
  esac
done
[ -n "$BATCH_DIR" ] || fail "--batch-dir is required"
[ -n "$OUTPUT_DIR" ] || fail "--output-dir is required"
[ -d "$BATCH_DIR" ] || fail "--batch-dir does not exist or is not a directory: ${BATCH_DIR}"
[ -f "${BATCH_DIR}/batch-manifest.json" ] || fail "--batch-dir has no batch-manifest.json — is this an extracted batch directory (not the .tar archive)?"
[ -e "$OUTPUT_DIR" ] && fail "--output-dir already exists — refusing to overwrite (${OUTPUT_DIR})"

json_escape() { python3 -c 'import json, sys; print(json.dumps(sys.argv[1]))' "$1" 2>/dev/null || printf '"unavailable"'; }

ORIGINAL_BATCH_ID="$(python3 -c "import json; print(json.load(open('${BATCH_DIR}/batch-manifest.json')).get('batchId', 'unknown'))")"
DERIVED_BATCH_ID="${ORIGINAL_BATCH_ID}-reindexed-$(date -u +%Y%m%dT%H%M%SZ)"
echo "== reindex-evidence: ${ORIGINAL_BATCH_ID} -> ${DERIVED_BATCH_ID}"
echo "   source (read-only):  ${BATCH_DIR}"
echo "   output (new, derived): ${OUTPUT_DIR}"
echo "   measurements are NOT rerun — this tool only regenerates manifests, indexes and hashes from artifacts already on disk."

# --- Per-variant component classification, driven by the lab's resolved
# policy + files actually present (never fabricated). This mirrors
# run-linux-evidence.sh's six-state component model and its
# resolve_profiler_action/lab_variant_kind resolution exactly, but reads
# outcomes from disk instead of live execution state.
resolve_variant_policy() {
  # resolve_variant_policy <lab-conf> <profile> <variant> — prints
  # "KIND=<jmh|aux> RUN_STAT=<0|1> RUN_C2C=<0|1> RUST_CMD=<0|1> LAB_RUST=<0|1>"
  local conf="$1" profile="$2" variant="$3"
  bash -c "
    set -e
    source '${SCRIPT_DIR}/lib/evidence-lib.sh'
    source '$conf'
    PROFILE='$profile'
    variant_profiler_policy() { if declare -f lab_profiler_policy >/dev/null 2>&1; then lab_profiler_policy \"\$1\"; else echo \"\${LAB_PROFILER_POLICY:-stat}\"; fi; }
    c2c_representative_variants() { if declare -f lab_c2c_variants >/dev/null 2>&1; then lab_c2c_variants; else lab_variants; fi; }
    is_c2c_representative_variant() { case \" \$(c2c_representative_variants) \" in *\" \$1 \"*) return 0 ;; *) return 1 ;; esac; }
    POLICY=\"\$(variant_profiler_policy '$variant')\"
    RUN_STAT=1; RUN_C2C=0
    [ \"\$POLICY\" = none ] && RUN_STAT=0
    case \"\$PROFILE\" in
      smoke|publication-sweep) RUN_C2C=0 ;;
      *) case \"\$POLICY\" in
           c2c) RUN_C2C=1 ;;
           c2c-core-only) if is_c2c_representative_variant '$variant'; then RUN_C2C=1; fi ;;
         esac ;;
    esac
    if declare -f lab_variant_kind >/dev/null 2>&1; then KIND=\"\$(lab_variant_kind '$variant')\"; else KIND=jmh; fi
    RUST_CMD_PRESENT=0
    [ -n \"\$(lab_rust_evidence_cmd '$variant' 2>/dev/null || true)\" ] && RUST_CMD_PRESENT=1
    LAB_RUST_PRESENT=0
    [ -n \"\${LAB_RUST_DIR:-}\" ] && LAB_RUST_PRESENT=1
    echo \"KIND=\${KIND} RUN_STAT=\${RUN_STAT} RUN_C2C=\${RUN_C2C} RUST_CMD=\${RUST_CMD_PRESENT} LAB_RUST=\${LAB_RUST_PRESENT}\"
  " 2>/dev/null || echo "KIND=jmh RUN_STAT=0 RUN_C2C=0 RUST_CMD=0 LAB_RUST=0"
}

ridx_not_applicable() { printf '{"status":"not-applicable","reason":%s}' "$(json_escape "$1")"; }
ridx_not_scheduled()  { printf '{"status":"not-scheduled","reason":%s}' "$(json_escape "$1")"; }
ridx_failed_missing() { printf '{"status":"failed","reason":%s}' "$(json_escape "expected but genuinely missing on disk: $1")"; }

TRANSFORMATIONS_FILE="$(mktemp)"
note_transform() { printf '%s\n' "$1" >> "$TRANSFORMATIONS_FILE"; }

reindex_one_variant() {
  # reindex_one_variant <run-dir> <lab> <profile> <variant> <component-selection>
  local run_dir="$1" lab="$2" profile="$3" v="$4" component_selection="$5" vdir="${1}/${4}"
  if [ "$component_selection" = "rust-harness" ]; then
    local reason="component=rust-harness (original focused run) — no JMH/aux/perf/placement execution in this mode"
    local rust_json
    if [ -f "${vdir}/rust-evidence.json" ] && [ -f "${vdir}/rust-perf-stat.csv" ]; then
      rust_json="{\"status\":\"completed\",\"artifacts\":{\"evidence\":\"${v}/rust-evidence.json\",\"perfStat\":\"${v}/rust-perf-stat.csv\"}}"
    else
      rust_json="$(ridx_not_scheduled "no rust-harness artifacts found on disk for this focused-component run")"
    fi
    printf '"%s": {"kind":"unknown","components":{"jmh":%s,"auxHarness":%s,"perfStat":%s,"perfC2c":%s,"rustHarness":%s,"workerPlacement":%s}}' \
      "$v" "$(ridx_not_applicable "$reason")" "$(ridx_not_applicable "$reason")" "$(ridx_not_applicable "$reason")" "$(ridx_not_applicable "$reason")" "$rust_json" "$(ridx_not_applicable "$reason")"
    return 0
  fi

  local conf="${SCRIPT_DIR}/labs/${lab}.conf"
  local policy_line kind run_stat run_c2c rust_cmd lab_rust
  policy_line="$(resolve_variant_policy "$conf" "$profile" "$v")"
  eval "$policy_line"
  kind="$KIND"; run_stat="$RUN_STAT"; run_c2c="$RUN_C2C"; rust_cmd="$RUST_CMD"; lab_rust="$LAB_RUST"

  local jmh_json aux_json perfstat_json perfc2c_json rust_json placement_json

  if [ "$kind" = "aux" ]; then
    jmh_json="$(ridx_not_applicable "variant kind=aux — no JMH invocation for this variant")"
    if [ -f "${vdir}/aux-evidence.json" ]; then
      aux_json="{\"status\":\"completed\",\"artifacts\":{\"result\":\"${v}/aux-evidence.json\"}}"
    else
      aux_json="$(ridx_failed_missing "${v}/aux-evidence.json")"
    fi
    perfstat_json="$(ridx_not_applicable "variant kind=aux — perf stat is only collected for jmh-kind variants")"
    perfc2c_json="$(ridx_not_applicable "variant kind=aux — perf c2c is only collected for jmh-kind variants")"
  else
    if [ -f "${vdir}/jmh.json" ]; then
      jmh_json="{\"status\":\"completed\",\"artifacts\":{\"result\":\"${v}/jmh.json\"}}"
    else
      jmh_json="$(ridx_failed_missing "${v}/jmh.json")"
    fi
    aux_json="$(ridx_not_applicable "variant kind=jmh — no aux harness for this variant")"
    if [ "$run_stat" = "1" ]; then
      if [ -f "${vdir}/perf-stat.csv" ] && [ -f "${vdir}/perf-stat-jmh.json" ]; then
        local counters="\"${v}/perf-stat.csv\"" summaries="\"${v}/perf-stat-jmh.json\"" i=2
        while [ -f "${vdir}/perf-stat-r${i}.csv" ] && [ -f "${vdir}/perf-stat-jmh-r${i}.json" ]; do
          counters="${counters}, \"${v}/perf-stat-r${i}.csv\""
          summaries="${summaries}, \"${v}/perf-stat-jmh-r${i}.json\""
          i=$((i + 1))
        done
        perfstat_json="{\"status\":\"completed\",\"artifacts\":{\"counters\":[${counters}],\"jmhSummaries\":[${summaries}]}}"
      else
        perfstat_json="$(ridx_failed_missing "${v}/perf-stat.csv")"
      fi
    else
      perfstat_json="$(ridx_not_scheduled "profiler policy resolved perf stat = not selected for variant ${v} under profile ${profile}")"
    fi
    if [ "$run_c2c" = "1" ]; then
      if [ -f "${vdir}/perf-c2c-report.txt" ] && [ -f "${vdir}/raw-profiler-retention.json" ]; then
        local retained sha bytes status
        retained="$(python3 -c "import json; print(json.dumps(json.load(open('${vdir}/raw-profiler-retention.json')).get('rawProfilerRetained')))" 2>/dev/null || echo null)"
        sha="$(python3 -c "import json; print(json.dumps(json.load(open('${vdir}/raw-profiler-retention.json')).get('rawProfilerSha256')))" 2>/dev/null || echo null)"
        bytes="$(python3 -c "import json; print(json.dumps(json.load(open('${vdir}/raw-profiler-retention.json')).get('rawProfilerOriginalBytes')))" 2>/dev/null || echo null)"
        status="retained-summary-only"; [ "$retained" = "true" ] && status="completed"
        perfc2c_json="{\"status\":\"${status}\",\"report\":\"${v}/perf-c2c-report.txt\",\"retentionMetadata\":\"${v}/raw-profiler-retention.json\",\"rawProfiler\":{\"retained\":${retained},\"sha256BeforeDeletion\":${sha},\"originalBytes\":${bytes}}}"
      else
        perfc2c_json="$(ridx_failed_missing "${v}/perf-c2c-report.txt")"
      fi
    else
      perfc2c_json="$(ridx_not_scheduled "profiler policy resolved perf c2c = not selected for variant ${v} under profile ${profile}")"
    fi
  fi

  if [ "$lab_rust" != "1" ]; then
    rust_json="$(ridx_not_applicable "lab ${lab} has no Rust harness (LAB_RUST_DIR unset)")"
  elif [ "$rust_cmd" != "1" ]; then
    rust_json="$(ridx_not_applicable "variant ${v} does not support a Rust harness (lab_rust_evidence_cmd returned no command)")"
  elif [ -f "${vdir}/rust-evidence.json" ] && [ -f "${vdir}/rust-perf-stat.csv" ]; then
    rust_json="{\"status\":\"completed\",\"artifacts\":{\"evidence\":\"${v}/rust-evidence.json\",\"perfStat\":\"${v}/rust-perf-stat.csv\"}}"
  else
    rust_json="$(ridx_failed_missing "${v}/rust-evidence.json")"
  fi

  if [ -f "${vdir}/jmh-placement.csv" ] && [ -f "${vdir}/placement-policy.json" ]; then
    local per_worker="null" per_worker_state="unavailable"
    if [ -f "${vdir}/worker-placement.json" ]; then per_worker="\"${v}/worker-placement.json\""; per_worker_state="verified"; fi
    placement_json="{\"status\":\"completed\",\"artifacts\":{\"aggregate\":\"${v}/jmh-placement.csv\",\"policy\":\"${v}/placement-policy.json\"},\"perWorkerPinning\":\"${per_worker_state}\",\"perWorkerArtifact\":${per_worker}}"
  else
    placement_json="$(ridx_failed_missing "${v}/jmh-placement.csv")"
  fi

  printf '"%s": {"kind":"%s","components":{"jmh":%s,"auxHarness":%s,"perfStat":%s,"perfC2c":%s,"rustHarness":%s,"workerPlacement":%s}}' \
    "$v" "$kind" "$jmh_json" "$aux_json" "$perfstat_json" "$perfc2c_json" "$rust_json" "$placement_json"
}

reindex_one_run() {
  # reindex_one_run <run-dir> <lab> — rewrites run-dir/evidence-manifest.json
  # in place (inside OUTPUT_DIR, never inside BATCH_DIR) and re-hashes the
  # run directory. Prints "pass" or "fail" (whether the regenerated
  # manifest now verifies cleanly against verify-evidence.sh) to stdout.
  local run_dir="$1" lab="$2"
  local old_manifest="${run_dir}/evidence-manifest.json"
  local profile component_selection
  profile="$(python3 -c "import json; print(json.load(open('${old_manifest}')).get('profile','publication-core'))")"
  component_selection="$(python3 -c "import json; print(json.load(open('${old_manifest}')).get('componentSelection','all'))")"

  local variants=""
  for vdir in "${run_dir}"/*/; do
    [ -d "$vdir" ] || continue
    local vname; vname="$(basename "$vdir")"
    [ -f "${vdir}run-status.json" ] && continue  # not a variant dir
    variants="${variants}${variants:+ }${vname}"
  done

  python3 - "$old_manifest" > "${old_manifest}.new" <<PYEOF
import json
with open("${old_manifest}") as fh:
    m = json.load(fh)
m["reindexed"] = {"tool": "reindex-evidence.sh", "originalBatchId": "${ORIGINAL_BATCH_ID}", "at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"}
print(json.dumps(m, indent=2))
PYEOF
  mv "${old_manifest}.new" "$old_manifest"
  # Splice the regenerated "variants" object over the stale one (component
  # model, never the old flat per-variant template).
  python3 - "$old_manifest" <<PYEOF
import json
with open("${old_manifest}") as fh:
    m = json.load(fh)
variants = {}
$(for v in $variants; do
  entry_json="$(reindex_one_variant "$run_dir" "$lab" "$profile" "$v" "$component_selection")"
  printf 'variants.update(json.loads(%s))\n' "$(python3 -c 'import json,sys; print(json.dumps("{" + sys.argv[1] + "}"))' "$entry_json")"
done)
m["variants"] = variants
with open("${old_manifest}", "w") as fh:
    json.dump(m, fh, indent=2)
    fh.write("\n")
PYEOF

  write_sha256sums "$run_dir" >/dev/null 2>&1 || true

  # Archive layout mirrors run-linux-evidence.sh exactly: run_dir is
  # <rep_dir>/<lab>/<run-id>, and the archive sits directly in <rep_dir>
  # (two levels up from run_dir, not one) with the archive's own top-level
  # entry being just <run-id> (tar -C <lab-dir> <run-id>).
  local lab_dir rep_dir archive
  lab_dir="$(dirname "$run_dir")"
  rep_dir="$(dirname "$lab_dir")"
  archive="$(ls "${rep_dir}/${lab}-"*"-linux-evidence.tar."* 2>/dev/null | head -1 || true)"
  if [ -n "$archive" ]; then
    local run_id; run_id="$(basename "$run_dir")"
    rm -f "$archive"
    case "$archive" in
      *.tar.zst) tar --zstd -cf "$archive" -C "$lab_dir" "$run_id" ;;
      *)         tar -czf "$archive" -C "$lab_dir" "$run_id" ;;
    esac
  fi

  if "${SCRIPT_DIR}/verify-evidence.sh" "$archive" >/dev/null 2>&1; then
    echo pass
  else
    echo fail
  fi
}

# --- Plan / dry-run -----------------------------------------------------------
echo
echo "Runs found:"
PLAN_LINES=()
while IFS= read -r manifest_path; do
  run_dir="$(dirname "$manifest_path")"
  lab="$(python3 -c "import json; print(json.load(open('${manifest_path}')).get('labId','unknown'))")"
  old_status="unknown"
  rel="${run_dir#"$BATCH_DIR"/}"
  echo "  ${rel} (lab=${lab})"
  PLAN_LINES+=("${run_dir}|${lab}")
done < <(find "$BATCH_DIR" -name evidence-manifest.json | sort)

if [ "$DRY_RUN" = "1" ]; then
  echo
  echo "DRY RUN — nothing written. ${#PLAN_LINES[@]} run(s) would be reindexed into a new derived batch ${DERIVED_BATCH_ID} at ${OUTPUT_DIR}."
  echo "Measurements are never rerun by this tool, dry-run or otherwise."
  rm -f "$TRANSFORMATIONS_FILE"
  exit 0
fi

# --- Real repair: copy first (BATCH_DIR is never written to), then repair
# only the copy. ---------------------------------------------------------------
mkdir -p "$(dirname "$OUTPUT_DIR")"
cp -R "$BATCH_DIR" "$OUTPUT_DIR"

for entry in "${PLAN_LINES[@]+"${PLAN_LINES[@]}"}"; do
  run_dir_orig="${entry%%|*}"
  lab="${entry##*|}"
  run_dir="${OUTPUT_DIR}/${run_dir_orig#"$BATCH_DIR"/}"
  result="$(reindex_one_run "$run_dir" "$lab")"
  rel="${run_dir_orig#"$BATCH_DIR"/}"
  note_transform "${rel} (lab=${lab}): manifest regenerated from component model + on-disk artifacts -> verify-evidence.sh ${result}"
done

# --- Batch manifest: new derived id, provenance of the repair, per-run
# status upgraded ONLY where the regenerated manifest now genuinely
# verifies (never upgraded blindly) ---------------------------------------
python3 - "$OUTPUT_DIR" "$ORIGINAL_BATCH_ID" "$DERIVED_BATCH_ID" "$TRANSFORMATIONS_FILE" <<'PYEOF'
import json, sys
from datetime import datetime, timezone
output_dir, original_id, derived_id, transformations_file = sys.argv[1:5]
manifest_path = f"{output_dir}/batch-manifest.json"
with open(manifest_path) as fh:
    manifest = json.load(fh)
with open(transformations_file) as fh:
    transformations = [l.rstrip("\n") for l in fh if l.strip()]

manifest["batchId"] = derived_id
manifest["derivedFrom"] = original_id
manifest["repairTool"] = "reindex-evidence.sh"
manifest["repairedAt"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
manifest["measurementsRerun"] = False
manifest["transformations"] = transformations
with open(manifest_path, "w") as fh:
    json.dump(manifest, fh, indent=2)
    fh.write("\n")
PYEOF

# Reclassify verification-failed runs whose regenerated manifest now
# verifies cleanly (never touching rejected-or-failed/timeout/unstable —
# those are genuine measurement outcomes, not manifest-generation defects).
for entry in "${PLAN_LINES[@]+"${PLAN_LINES[@]}"}"; do
  run_dir_orig="${entry%%|*}"
  lab="${entry##*|}"
  run_dir="${OUTPUT_DIR}/${run_dir_orig#"$BATCH_DIR"/}"
  rep_dir="$(dirname "$(dirname "$run_dir")")"
  archive="$(ls "${rep_dir}/${lab}-"*"-linux-evidence.tar."* 2>/dev/null | head -1 || true)"
  [ -n "$archive" ] || continue
  # run_dir_orig looks like <BATCH_DIR>/run-<rep>/<lab> — the repetition
  # number is the one piece of identity that ties this run directory back
  # to its exact entry in batch-manifest.json.
  REP="$(basename "$rep_dir" | sed 's/^run-//')"
  if "${SCRIPT_DIR}/verify-evidence.sh" "$archive" >/dev/null 2>&1; then
    HASH="$(shasum -a 256 "$archive" 2>/dev/null || sha256sum "$archive")"
    python3 - "${OUTPUT_DIR}/batch-manifest.json" "$lab" "$REP" "$archive" "$OUTPUT_DIR" "${HASH%% *}" <<'PYEOF'
import json, os, sys
manifest_path, lab, rep, archive, output_dir, sha = sys.argv[1:7]
with open(manifest_path) as fh:
    manifest = json.load(fh)
rel_archive = os.path.relpath(archive, output_dir)
for run in manifest.get("labs", {}).get(lab, {}).get("runs", []):
    if str(run.get("repetition")) != rep:
        continue
    if run.get("status") == "verification-failed":
        run["status"] = "collected"
        run["archive"] = rel_archive
        run["sha256"] = sha
        run["reindexedFrom"] = "verification-failed"
    break
with open(manifest_path, "w") as fh:
    json.dump(manifest, fh, indent=2)
    fh.write("\n")
PYEOF
  fi
done

# Recompute batch state ONLY as a function of the (possibly now-upgraded)
# run statuses actually recorded above — never forced to "complete";
# any lab still short of its repetitions, or still carrying a genuine
# rejected-or-failed/timeout/unstable-host run, keeps the batch "partial".
python3 - "${OUTPUT_DIR}/batch-manifest.json" <<'PYEOF'
import json, sys
manifest_path = sys.argv[1]
with open(manifest_path) as fh:
    manifest = json.load(fh)
if manifest.get("state") in ("partial", "complete"):
    reps = manifest.get("repetitions", 0)
    all_collected = all(
        len([r for r in entry.get("runs", []) if r.get("status") == "collected"]) >= reps
        and all(r.get("status") == "collected" for r in entry.get("runs", []))
        for entry in (manifest.get("labs") or {}).values()
    )
    manifest["state"] = "complete" if all_collected else "partial"
with open(manifest_path, "w") as fh:
    json.dump(manifest, fh, indent=2)
    fh.write("\n")
PYEOF

write_sha256sums "$OUTPUT_DIR" >/dev/null 2>&1 || true
OUTER_ARCHIVE="$(dirname "$OUTPUT_DIR")/performance-lab-${DERIVED_BATCH_ID}.tar.gz"
if command -v zstd >/dev/null 2>&1; then
  OUTER_ARCHIVE="$(dirname "$OUTPUT_DIR")/performance-lab-${DERIVED_BATCH_ID}.tar.zst"
  tar --zstd -cf "$OUTER_ARCHIVE" -C "$(dirname "$OUTPUT_DIR")" "$(basename "$OUTPUT_DIR")"
else
  tar -czf "$OUTER_ARCHIVE" -C "$(dirname "$OUTPUT_DIR")" "$(basename "$OUTPUT_DIR")"
fi

echo
echo "== DONE (measurements were NOT rerun)"
echo "Original batch (untouched): ${BATCH_DIR} (${ORIGINAL_BATCH_ID})"
echo "Derived, repaired batch:    ${OUTPUT_DIR} (${DERIVED_BATCH_ID})"
echo "Derived archive:            ${OUTER_ARCHIVE}"
echo "Transformations:"
sed 's/^/  /' "$TRANSFORMATIONS_FILE"
rm -f "$TRANSFORMATIONS_FILE"
echo
echo "Next: ./scripts/performance-lab/verify-benchmark-batch.sh --strict ${OUTER_ARCHIVE}"
