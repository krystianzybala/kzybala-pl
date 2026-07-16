#!/usr/bin/env bash
# Verifies a complete batch evidence package produced by
# run-all-benchmarks.sh before import:
#   ./scripts/performance-lab/verify-benchmark-batch.sh \
#     results/batches/<batch-id>/performance-lab-<batch-id>.tar.zst
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ARCHIVE="${1:-}"
[ -n "$ARCHIVE" ] && [ -f "$ARCHIVE" ] || { echo "usage: verify-benchmark-batch.sh <performance-lab-<batch-id>.tar.zst|.tar.gz>" >&2; exit 2; }

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

echo "== checking manifest, repetitions, run ids, per-lab archives"
node - "$BATCH_DIR" "$SCRIPT_DIR" <<'NODEEOF'
const { readFileSync, existsSync, readdirSync, statSync } = require("node:fs");
const { execFileSync } = require("node:child_process");
const { join } = require("node:path");
const [batchDir, scriptDir] = process.argv.slice(2);
const problems = [];
const manifest = JSON.parse(readFileSync(join(batchDir, "batch-manifest.json"), "utf8"));

for (const key of ["batchId", "hostName", "profile", "repetitions", "sourceCommit", "executionOrder", "labs", "state"]) {
  if (!(key in manifest)) problems.push(`manifest missing "${key}"`);
}
if (!["complete", "partial", "rejected", "failed-preflight", "failed-infrastructure"].includes(manifest.state)) {
  problems.push(`manifest state "${manifest.state}" is not a known final state`);
}

const seenRunIds = new Set();
for (const [lab, entry] of Object.entries(manifest.labs ?? {})) {
  const runs = entry.runs ?? [];
  const collected = runs.filter((r) => r.status === "collected");
  // rejected/failed runs never count toward the repetition requirement
  if (manifest.state === "complete" && collected.length < manifest.repetitions) {
    problems.push(`${lab}: state "complete" but only ${collected.length}/${manifest.repetitions} collected repetitions`);
  }
  for (const run of collected) {
    const archivePath = join(batchDir, run.archive);
    if (!existsSync(archivePath)) {
      problems.push(`${lab}: archive ${run.archive} missing`);
      continue;
    }
    const hash = execFileSync(process.platform === "darwin" ? "shasum" : "sha256sum",
      process.platform === "darwin" ? ["-a", "256", archivePath] : [archivePath], { encoding: "utf8" }).split(/\s+/)[0];
    if (hash !== run.sha256) problems.push(`${lab}: archive ${run.archive} hash mismatch`);
    // per-lab archives must themselves verify (evidence manifest, hashes)
    try {
      execFileSync(join(scriptDir, "verify-evidence.sh"), [archivePath], { stdio: "pipe" });
    } catch (err) {
      problems.push(`${lab}: per-lab evidence verification failed for ${run.archive}`);
    }
    const runId = run.archive.replace(/^.*\//, "");
    if (seenRunIds.has(runId)) problems.push(`duplicate run id ${runId}`);
    seenRunIds.add(runId);
  }
}
// every enabled lab present
for (const lab of manifest.executionOrder ?? []) {
  if (!manifest.labs?.[lab]) problems.push(`enabled lab ${lab} missing from manifest.labs`);
}
// containment + file-type restrictions: only regular files/dirs inside the batch
function* walk(dir) {
  for (const e of readdirSync(dir)) { const f = join(dir, e); const st = statSync(f, { throwIfNoEntry: false }); if (!st) continue; if (st.isDirectory()) yield* walk(f); else yield [f, st]; }
}
for (const [f, st] of walk(batchDir)) {
  if (!st.isFile()) problems.push(`unexpected non-regular file: ${f}`);
}
if (problems.length > 0) {
  console.error("verify-benchmark-batch: problems found:\n  " + problems.join("\n  "));
  process.exit(1);
}
console.log(`   batch ${manifest.batchId} (${manifest.state}) — ${Object.keys(manifest.labs).length} lab(s), ${manifest.repetitions} repetition(s), source ${manifest.sourceCommit?.slice(0, 12)}`);
NODEEOF

echo "== OK: batch verified. Next: ./scripts/performance-lab/import-benchmark-batch.sh ${ARCHIVE}"
