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

echo "== checking manifest references"
node - "$RUN_DIR" <<'NODEEOF'
const { readFileSync, existsSync } = require("node:fs");
const { join } = require("node:path");
const runDir = process.argv[2];
const manifest = JSON.parse(readFileSync(join(runDir, "evidence-manifest.json"), "utf8"));
const missing = [];
const check = (rel) => { if (typeof rel === "string" && !existsSync(join(runDir, rel))) missing.push(rel); };
for (const key of ["environment", "topology", "capabilities", "toolchain", "benchmarkProfile", "correctness"]) check(manifest[key]);
for (const variant of Object.values(manifest.variants ?? {})) {
  for (const value of Object.values(variant)) {
    if (Array.isArray(value)) value.forEach(check); else check(value);
  }
}
if (!manifest.sourceCommit) missing.push("<manifest.sourceCommit>");
if (missing.length > 0) {
  console.error("verify-evidence: manifest references missing artifacts:\n  " + missing.join("\n  "));
  process.exit(1);
}
console.log(`   manifest OK — lab ${manifest.labId}, run ${manifest.runId}, commit ${manifest.sourceCommit}, dirty=${manifest.dirtyTree}, scenario=${manifest.scenario}`);
NODEEOF

echo "== OK: archive verified. Next: ./scripts/performance-lab/import-evidence.sh ${ARCHIVE}"
