#!/usr/bin/env bash
# Imports a verified native-Linux evidence archive into results/<lab>/<run-id>/
# and produces the canonical records + derived comparison via the plab-003
# importers. Importing NEVER promotes the lab or any importer capability —
# evidence maturity is derived, and human review is still required
# (docs/linux-evidence-runner.md).
#   ./scripts/performance-lab/import-evidence.sh false-sharing-<run-id>-linux-evidence.tar.zst
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ARCHIVE="${1:-}"
[ -n "$ARCHIVE" ] && [ -f "$ARCHIVE" ] || { echo "usage: import-evidence.sh <archive.tar.zst|.tar.gz>" >&2; exit 2; }

"${SCRIPT_DIR}/verify-evidence.sh" "$ARCHIVE"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
case "$ARCHIVE" in
  *.tar.zst) tar --zstd -xf "$ARCHIVE" -C "$TMP" ;;
  *.tar.gz)  tar -xzf "$ARCHIVE" -C "$TMP" ;;
esac
SRC_DIR="$(find "$TMP" -mindepth 1 -maxdepth 1 -type d | head -1)"
RUN_ID="$(basename "$SRC_DIR")"

LAB_ID="$(node -e 'console.log(JSON.parse(require("node:fs").readFileSync(process.argv[1]+"/evidence-manifest.json","utf8")).labId)' "$SRC_DIR")"
DEST="${REPO_ROOT}/results/${LAB_ID}/${RUN_ID}"
if [ -e "$DEST" ]; then
  echo "import-evidence: ${DEST} already exists — run directories are immutable; refusing to overwrite" >&2
  exit 1
fi
mkdir -p "$(dirname "$DEST")"
cp -R "$SRC_DIR" "$DEST"

node "${SCRIPT_DIR}/import-linux-evidence.mjs" "$DEST"

echo
echo "== imported: ${DEST}"
echo "Canonical records and comparison.json are in place. This did NOT mark"
echo "anything verified: review the artifacts against the checklist in"
echo "docs/linux-evidence-runner.md, then update the lab content and (only"
echo "after review) the evidence/reproduction fields in a follow-up import."
