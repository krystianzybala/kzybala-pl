#!/usr/bin/env bash
# Verifies a complete batch evidence package produced by
# run-all-benchmarks.sh before import. Archive INTEGRITY (extraction,
# SHA-256 sums, archive structure, manifest structure) is a strictly
# separate question from EVIDENCE ACCEPTANCE (every requested repetition
# actually collected, every per-lab evidence verification passing, no
# verification-failed/rejected-or-failed runs, valid publication
# eligibility, valid provenance invariants) — a batch can be perfectly
# intact as an archive while every measurement inside it was rejected.
#   ./scripts/performance-lab/verify-benchmark-batch.sh --integrity-only \
#     results/batches/<batch-id>/performance-lab-<batch-id>.tar.zst
#   ./scripts/performance-lab/verify-benchmark-batch.sh --strict \
#     results/batches/<batch-id>/performance-lab-<batch-id>.tar.zst
# No mode flag is a deprecated alias for --integrity-only (structural
# checks only) — it prints a warning saying evidence acceptance was never
# checked; callers that need import-grade assurance must pass --strict.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODE="integrity-only-implicit"
ARCHIVE=""
for arg in "$@"; do
  case "$arg" in
    --integrity-only) MODE="integrity-only" ;;
    --strict) MODE="strict" ;;
    -*) echo "verify-benchmark-batch: unknown flag: ${arg}" >&2; exit 2 ;;
    *) ARCHIVE="$arg" ;;
  esac
done
[ -n "$ARCHIVE" ] && [ -f "$ARCHIVE" ] || { echo "usage: verify-benchmark-batch.sh [--integrity-only|--strict] <performance-lab-<batch-id>.tar.zst|.tar.gz>" >&2; exit 2; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
case "$ARCHIVE" in
  *.tar.zst) tar --zstd -xf "$ARCHIVE" -C "$TMP" ;;
  *.tar.gz)  tar -xzf "$ARCHIVE" -C "$TMP" ;;
  *) echo "verify-benchmark-batch: unrecognized archive extension" >&2; exit 2 ;;
esac
BATCH_DIR="$(find "$TMP" -mindepth 1 -maxdepth 1 -type d | head -1)"
[ -n "$BATCH_DIR" ] || { echo "verify-benchmark-batch: archive contains no batch directory" >&2; exit 1; }

echo "== checking batch SHA-256 sums"
(
  cd "$BATCH_DIR"
  # The outer batch archive itself is excluded from its own sums.
  grep -v "performance-lab-" SHA256SUMS > SHA256SUMS.check || true
  if command -v sha256sum >/dev/null 2>&1; then sha256sum -c SHA256SUMS.check; else shasum -a 256 -c SHA256SUMS.check; fi
  rm -f SHA256SUMS.check
) > "$TMP/shacheck.log" 2>&1 || { echo "verify-benchmark-batch: HASH MISMATCH:"; grep -v ": OK$" "$TMP/shacheck.log" | head; exit 1; }
echo "   all hashes OK"

if [ "$MODE" = "integrity-only-implicit" ]; then
  echo "== WARNING: no --integrity-only/--strict flag given — running integrity-only checks (archive/manifest structure). Evidence acceptance (repetitions collected, per-lab verification, provenance) was NOT checked. Pass --strict for import-grade assurance." >&2
fi

echo "== checking archive integrity (extraction, hashes, manifest/archive structure)"
node - "$BATCH_DIR" "$SCRIPT_DIR" "$MODE" <<'NODEEOF'
const { readFileSync, existsSync, readdirSync, statSync } = require("node:fs");
const { execFileSync } = require("node:child_process");
const { join } = require("node:path");
const [batchDir, scriptDir, mode] = process.argv.slice(2);
const strict = mode === "strict";
const integrityProblems = [];
const evidenceProblems = [];
const manifest = JSON.parse(readFileSync(join(batchDir, "batch-manifest.json"), "utf8"));

// --- Archive integrity: manifest structure -----------------------------------
for (const key of ["batchId", "hostName", "profile", "repetitions", "sourceCommit", "executionOrder", "labs", "state"]) {
  if (!(key in manifest)) integrityProblems.push(`manifest missing "${key}"`);
}
const KNOWN_STATES = ["complete", "partial", "rejected", "failed-preflight", "failed-preflight-source-mutation", "failed-infrastructure", "failed-storage-budget", "failed-benchmark-timeout"];
if (!KNOWN_STATES.includes(manifest.state)) {
  integrityProblems.push(`manifest state "${manifest.state}" is not a known final state`);
}
const KNOWN_RUN_STATUSES = new Set(["collected", "verification-failed", "rejected-or-failed", "blocked-unstable-host", "failed-environment-capture", "failed-storage-budget", "failed-benchmark-timeout", "invalid-stability-metric"]);

const seenRunIds = new Set();
for (const [lab, entry] of Object.entries(manifest.labs ?? {})) {
  const runs = entry.runs ?? [];
  for (const run of runs) {
    if (!KNOWN_RUN_STATUSES.has(run.status)) integrityProblems.push(`${lab}: unknown run status "${run.status}"`);
    if (run.status !== "collected") continue;
    // archive hash/existence is an INTEGRITY question for whatever was
    // actually produced, independent of whether the batch as a whole
    // satisfied its repetition/acceptance requirements.
    const archivePath = join(batchDir, run.archive ?? "");
    if (!run.archive || !existsSync(archivePath)) {
      integrityProblems.push(`${lab}: archive ${run.archive ?? "<missing path>"} missing`);
      continue;
    }
    const hash = execFileSync(process.platform === "darwin" ? "shasum" : "sha256sum",
      process.platform === "darwin" ? ["-a", "256", archivePath] : [archivePath], { encoding: "utf8" }).split(/\s+/)[0];
    if (hash !== run.sha256) integrityProblems.push(`${lab}: archive ${run.archive} hash mismatch`);
    const runId = run.archive.replace(/^.*\//, "");
    if (seenRunIds.has(runId)) integrityProblems.push(`duplicate run id ${runId}`);
    seenRunIds.add(runId);
  }
}
for (const lab of manifest.executionOrder ?? []) {
  if (!manifest.labs?.[lab]) integrityProblems.push(`enabled lab ${lab} missing from manifest.labs`);
}
function* walk(dir) {
  for (const e of readdirSync(dir)) { const f = join(dir, e); const st = statSync(f, { throwIfNoEntry: false }); if (!st) continue; if (st.isDirectory()) yield* walk(f); else yield [f, st]; }
}
for (const [f, st] of walk(batchDir)) {
  if (!st.isFile()) integrityProblems.push(`unexpected non-regular file: ${f}`);
}

if (integrityProblems.length > 0) {
  console.error("verify-benchmark-batch: archive integrity FAILED:\n  " + integrityProblems.join("\n  "));
  process.exit(1);
}
console.log(`   archive integrity verified — batch ${manifest.batchId}, ${Object.keys(manifest.labs).length} lab(s), ${manifest.repetitions} repetition(s) requested, source ${manifest.sourceCommit?.slice(0, 12)}`);

if (!strict) {
  console.log("   (--integrity-only: evidence acceptance was not evaluated — every requested repetition may or may not have been collected/accepted)");
  process.exit(0);
}

// --- Evidence acceptance (--strict only) -------------------------------------
console.log("== checking evidence acceptance (state, repetitions, per-lab verification, provenance)");
if (manifest.state !== "complete") {
  evidenceProblems.push(`batch state is "${manifest.state}", not "complete"`);
}
if (manifest.dirtyTree === true && manifest.publicationEligible === true) {
  evidenceProblems.push("provenance invariant violated: dirtyTree=true with publicationEligible=true");
}
if (typeof manifest.publicationEligible !== "boolean") {
  evidenceProblems.push("manifest.publicationEligible is missing or not a boolean");
}

for (const [lab, entry] of Object.entries(manifest.labs ?? {})) {
  const runs = entry.runs ?? [];
  const collected = runs.filter((r) => r.status === "collected");
  const notCollected = runs.filter((r) => r.status !== "collected");
  if (collected.length < manifest.repetitions) {
    evidenceProblems.push(`${lab}: only ${collected.length}/${manifest.repetitions} requested repetitions were accepted (collected)`);
  }
  for (const bad of notCollected) {
    const detail = bad.status === "verification-failed"
      ? ` (reasonCode=${bad.reasonCode ?? "unspecified"}, log=${bad.verificationLog ?? "unspecified"})`
      : bad.status === "rejected-or-failed"
        ? ` (reason=${bad.reason ?? "unspecified"}, console=${bad.console ?? "unspecified"})`
        : "";
    evidenceProblems.push(`${lab}: repetition ${bad.repetition} has status "${bad.status}"${detail} — evidence acceptance failed`);
  }
  for (const run of collected) {
    const archivePath = join(batchDir, run.archive);
    try {
      execFileSync(join(scriptDir, "verify-evidence.sh"), [archivePath], { stdio: "pipe" });
    } catch (err) {
      evidenceProblems.push(`${lab}: per-lab evidence verification failed for ${run.archive}: ${(err.stdout ?? err.stderr ?? "").toString().trim().split("\n").pop()}`);
    }
  }
}

if (evidenceProblems.length > 0) {
  console.error("verify-benchmark-batch: evidence acceptance FAILED:\n  " + evidenceProblems.join("\n  "));
  process.exit(1);
}
console.log(`   evidence acceptance verified — every lab has ${manifest.repetitions}/${manifest.repetitions} accepted repetitions, no verification-failed/rejected-or-failed runs, provenance invariants hold`);
NODEEOF

if [ "$MODE" = "strict" ]; then
  echo "== OK: archive integrity verified; evidence acceptance verified (--strict). Next: ./scripts/performance-lab/import-benchmark-batch.sh ${ARCHIVE}"
else
  echo "== OK: archive integrity verified (evidence acceptance NOT checked — rerun with --strict before import)."
fi
