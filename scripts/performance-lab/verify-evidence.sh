#!/usr/bin/env bash
# Verifies a native-Linux evidence archive produced by
# run-linux-evidence.sh before it is imported: extracts to a temp
# directory, checks every SHA-256, and sanity-checks the manifest.
#   ./scripts/performance-lab/verify-evidence.sh false-sharing-<run-id>-linux-evidence.tar.zst
set -euo pipefail

ARCHIVE="${1:-}"
[ -n "$ARCHIVE" ] && [ -f "$ARCHIVE" ] || { echo "usage: verify-evidence.sh <archive.tar.zst|.tar.gz>" >&2; exit 2; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

case "$ARCHIVE" in
  *.tar.zst) tar --zstd -xf "$ARCHIVE" -C "$TMP" ;;
  *.tar.gz)  tar -xzf "$ARCHIVE" -C "$TMP" ;;
  *) echo "verify-evidence: unrecognized archive extension (expected .tar.zst or .tar.gz)" >&2; exit 2 ;;
esac

RUN_DIR="$(find "$TMP" -mindepth 1 -maxdepth 1 -type d | head -1)"
[ -n "$RUN_DIR" ] || { echo "verify-evidence: archive contains no run directory" >&2; exit 1; }
[ -f "$RUN_DIR/SHA256SUMS" ] || { echo "verify-evidence: SHA256SUMS missing" >&2; exit 1; }
[ -f "$RUN_DIR/evidence-manifest.json" ] || { echo "verify-evidence: evidence-manifest.json missing" >&2; exit 1; }

echo "== checking SHA-256 sums"
(
  cd "$RUN_DIR"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum -c SHA256SUMS
  else
    shasum -a 256 -c SHA256SUMS
  fi
) > "$TMP/shacheck.log" 2>&1 || { echo "verify-evidence: HASH MISMATCH — evidence is not intact:"; grep -v ": OK$" "$TMP/shacheck.log" | head; exit 1; }
echo "   all hashes OK ($(grep -c ": OK$" "$TMP/shacheck.log" || true) files)"

echo "== checking manifest references (component-status aware)"
node - "$RUN_DIR" <<'NODEEOF'
const { readFileSync, existsSync } = require("node:fs");
const { join } = require("node:path");
const runDir = process.argv[2];
const manifest = JSON.parse(readFileSync(join(runDir, "evidence-manifest.json"), "utf8"));
const problems = [];
const exists = (rel) => typeof rel === "string" && existsSync(join(runDir, rel));
const requireFile = (rel, ctx) => { if (!exists(rel)) problems.push(`${ctx}: missing artifact ${rel}`); };

for (const key of ["environment", "topology", "capabilities", "toolchain", "benchmarkProfile", "correctness"]) {
  requireFile(manifest[key], `top-level ${key}`);
}
if (!manifest.sourceCommit) problems.push("manifest.sourceCommit is missing/empty");

// Provenance invariant (independently re-checked here — never trust a
// single call site): a dirty tracked tree can never be publication
// eligible. This is a manifest-generation defect, not a per-run
// verification-failed reason a rerun could fix, so it is reported as such.
if (manifest.dirtyTree === true && manifest.publicationEligible === true) {
  problems.push("provenance invariant violated: dirtyTree=true with publicationEligible=true");
}

const SHA256_RE = /^[0-9a-f]{64}$/;
const KNOWN_STATUSES = new Set(["completed", "not-scheduled", "not-applicable", "failed", "timed-out", "retained-summary-only"]);

function checkNoRequiredPaths(status, comp, ctx) {
  // not-scheduled/not-applicable must declare zero required artifact paths.
  if (comp.artifacts !== undefined || comp.report !== undefined || comp.retentionMetadata !== undefined) {
    problems.push(`${ctx}: status=${status} must not declare artifact paths`);
  }
}

function checkArtifactsObject(artifacts, fields, ctx) {
  for (const field of fields) {
    const val = artifacts?.[field];
    if (Array.isArray(val)) {
      if (val.length === 0) problems.push(`${ctx}: artifacts.${field} is an empty array`);
      val.forEach((p) => requireFile(p, `${ctx} (artifacts.${field})`));
    } else {
      requireFile(val, `${ctx} (artifacts.${field})`);
    }
  }
}

function checkPerfC2c(comp, ctx) {
  requireFile(comp.report, `${ctx} (report)`);
  requireFile(comp.retentionMetadata, `${ctx} (retentionMetadata)`);
  const raw = comp.rawProfiler ?? {};
  if (typeof raw.retained !== "boolean") problems.push(`${ctx}: rawProfiler.retained must be a boolean`);
  if (typeof raw.sha256BeforeDeletion !== "string" || !SHA256_RE.test(raw.sha256BeforeDeletion)) {
    problems.push(`${ctx}: rawProfiler.sha256BeforeDeletion is not a valid sha256 hex digest`);
  }
  if (!Number.isInteger(raw.originalBytes) || raw.originalBytes < 0) {
    problems.push(`${ctx}: rawProfiler.originalBytes must be a non-negative integer`);
  }
  if (comp.status === "completed") {
    // Retained raw profiler: the raw file itself must genuinely exist.
    if (raw.retained !== true) problems.push(`${ctx}: status=completed requires rawProfiler.retained=true`);
  } else {
    // retained-summary-only: raw was deleted by policy — never required.
    if (raw.retained !== false) problems.push(`${ctx}: status=retained-summary-only requires rawProfiler.retained=false`);
  }
}

const COMPONENT_NAMES = ["jmh", "auxHarness", "perfStat", "perfC2c", "rustHarness", "workerPlacement"];
for (const [variantName, variant] of Object.entries(manifest.variants ?? {})) {
  const components = variant.components ?? variant; // tolerate flat legacy shape defensively
  for (const name of COMPONENT_NAMES) {
    const comp = components[name];
    const ctx = `variant ${variantName}, component ${name}`;
    if (!comp || typeof comp !== "object") { problems.push(`${ctx}: missing component entry`); continue; }
    if (!KNOWN_STATUSES.has(comp.status)) { problems.push(`${ctx}: unknown status "${comp.status}"`); continue; }

    if (comp.status === "not-scheduled" || comp.status === "not-applicable") {
      checkNoRequiredPaths(comp.status, comp, ctx);
      continue;
    }
    if (comp.status === "failed" || comp.status === "timed-out") {
      // Bounded diagnostic artifacts MAY be declared; whatever is declared
      // must exist, but nothing is required. A genuinely failed/timed-out
      // component is never acceptable evidence on its own — in real live
      // execution this state can never even reach a finalized manifest
      // (mark_rejected/mark_timeout exit before one is written), so its
      // presence here means either a repair tool's honest "still broken"
      // finding (reindex-evidence.sh) or a hand-built manifest — either
      // way the whole archive must fail verification, never pass because
      // its OWN declared paths happened to all exist.
      if (Array.isArray(comp.diagnostics)) comp.diagnostics.forEach((p) => requireFile(p, `${ctx} (diagnostics)`));
      problems.push(`${ctx}: component status is "${comp.status}" — not acceptable evidence`);
      continue;
    }
    // completed / retained-summary-only from here on.
    if (name === "perfC2c") {
      if (comp.status !== "completed" && comp.status !== "retained-summary-only") {
        problems.push(`${ctx}: unexpected status "${comp.status}" for perfC2c`);
      }
      checkPerfC2c(comp, ctx);
      continue;
    }
    if (comp.status !== "completed") {
      problems.push(`${ctx}: status "${comp.status}" is only valid for perfC2c`);
      continue;
    }
    if (name === "jmh" || name === "auxHarness") checkArtifactsObject(comp.artifacts, ["result"], ctx);
    else if (name === "perfStat") checkArtifactsObject(comp.artifacts, ["counters", "jmhSummaries"], ctx);
    else if (name === "rustHarness") checkArtifactsObject(comp.artifacts, ["evidence", "perfStat"], ctx);
    else if (name === "workerPlacement") {
      checkArtifactsObject(comp.artifacts, ["aggregate", "policy"], ctx);
      if (comp.perWorkerArtifact !== null && comp.perWorkerArtifact !== undefined) {
        requireFile(comp.perWorkerArtifact, `${ctx} (perWorkerArtifact)`);
      }
    }
  }
}

if (problems.length > 0) {
  console.error("verify-evidence: manifest/evidence acceptance failed:\n  " + problems.join("\n  "));
  process.exit(1);
}
console.log(`   manifest OK — lab ${manifest.labId}, run ${manifest.runId}, commit ${manifest.sourceCommit}, dirty=${manifest.dirtyTree}, scenario=${manifest.scenario}`);
NODEEOF

echo "== OK: archive verified. Next: ./scripts/performance-lab/import-evidence.sh ${ARCHIVE}"
