#!/usr/bin/env bash
# Imports a verified batch evidence package: each lab independently, both
# repetitions preserved, separate provenance chains. Derives at most
# measured/reproduced — reviewed/verified/published always require human
# review, and public content updates happen in a separate reviewed pass
# (docs/measurement-environments.md).
#   ./scripts/performance-lab/import-benchmark-batch.sh \
#     results/batches/<batch-id>/performance-lab-<batch-id>.tar.zst
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ARCHIVE="${1:-}"
[ -n "$ARCHIVE" ] && [ -f "$ARCHIVE" ] || { echo "usage: import-benchmark-batch.sh <performance-lab-<batch-id>.tar.zst|.tar.gz>" >&2; exit 2; }

"${SCRIPT_DIR}/verify-benchmark-batch.sh" "$ARCHIVE"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
case "$ARCHIVE" in
  *.tar.zst) tar --zstd -xf "$ARCHIVE" -C "$TMP" ;;
  *.tar.gz)  tar -xzf "$ARCHIVE" -C "$TMP" ;;
esac
BATCH_DIR="$(find "$TMP" -mindepth 1 -maxdepth 1 -type d | head -1)"
BATCH_ID="$(basename "$BATCH_DIR")"
REPORT="${REPO_ROOT}/results/batches/${BATCH_ID}-import-report.json"
mkdir -p "${REPO_ROOT}/results/batches"

echo "{" > "$REPORT"
echo "  \"batchId\": \"${BATCH_ID}\"," >> "$REPORT"
echo "  \"importedAt\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"," >> "$REPORT"
echo "  \"labs\": {" >> "$REPORT"

FIRST=1
FAILED=0
for LAB_ARCHIVE in "$BATCH_DIR"/run-*/*-linux-evidence.tar.*; do
  [ -f "$LAB_ARCHIVE" ] || continue
  [ "$FIRST" = "1" ] || echo "," >> "$REPORT"
  FIRST=0
  NAME="$(basename "$LAB_ARCHIVE")"
  if "${SCRIPT_DIR}/import-evidence.sh" "$LAB_ARCHIVE" > "${TMP}/${NAME}.import.log" 2>&1; then
    STATUS="imported"
    # Derived maturity is printed by the importer and is at most draft/
    # reproduced ("measured" in the state ladder) — never reviewed/verified.
    MATURITY="$(grep -o 'derived evidence maturity[^\n]*' "${TMP}/${NAME}.import.log" | head -1 || echo "see import log")"
  else
    STATUS="failed"
    MATURITY="$(tail -1 "${TMP}/${NAME}.import.log" | head -c 160)"
    FAILED=1
  fi
  printf '    "%s": { "status": "%s", "detail": %s }' "$NAME" "$STATUS" "$(node -e 'console.log(JSON.stringify(process.argv[1]))' "$MATURITY")" >> "$REPORT"
  echo "  ${NAME}: ${STATUS}"
done

echo >> "$REPORT"
echo "  }," >> "$REPORT"
echo "  \"note\": \"import derives at most measured/reproduced; reviewed, verified and published require human review — public content updates happen in a separate reviewed pass\"" >> "$REPORT"
echo "}" >> "$REPORT"

echo
echo "Import report: ${REPORT}"
echo "Nothing on the public site was updated. Review checklist: docs/linux-evidence-runner.md; state ladder: docs/measurement-environments.md."
[ "$FAILED" = "0" ] || exit 1
