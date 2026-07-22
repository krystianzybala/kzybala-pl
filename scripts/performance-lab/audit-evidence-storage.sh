#!/usr/bin/env bash
# Deterministic storage inventory for the Performance Lab evidence tree
# (docs/evidence-storage-retention.md). Read-only: never deletes, never
# modifies anything, never itself writes a large file (the report is
# O(labs + variants + batches) plus a bounded top-N-largest-files list,
# never O(files)).
#
#   ./scripts/performance-lab/audit-evidence-storage.sh results
#   ./scripts/performance-lab/audit-evidence-storage.sh results --json
#   ./scripts/performance-lab/audit-evidence-storage.sh results --json --out /tmp/audit.json
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ $# -lt 1 ]; then
  echo "usage: $0 <results-root> [--json] [--out <file>]" >&2
  exit 2
fi

command -v python3 >/dev/null 2>&1 || { echo "audit-evidence-storage: python3 is required" >&2; exit 1; }

exec python3 "${SCRIPT_DIR}/lib/audit_evidence_storage.py" "$@"
