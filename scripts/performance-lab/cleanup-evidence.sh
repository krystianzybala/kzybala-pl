#!/usr/bin/env bash
# Safe retention cleanup for the Performance Lab evidence tree
# (docs/evidence-storage-retention.md). Defaults to --dry-run; deletion
# requires an explicit --apply. NEVER deletes a file classified
# "canonical" (imported/importable evidence) or anything outside
# --results-root (source files are never touched — this script never
# receives a path outside the results tree).
#
#   ./scripts/performance-lab/cleanup-evidence.sh \
#     --results-root results --policy failed-and-partial --dry-run
#
# Policies:
#   failed-and-partial  raw-profiler + temporary files inside batches/runs
#                        that did not complete successfully.
#   raw-profiler-only    every raw-profiler file anywhere under the root.
#   older-than           raw-profiler + temporary files older than
#                        --older-than-days N.
#   keep-canonical       raw-profiler + temporary + diagnostic anywhere —
#                        the aggressive "keep only canonical" sweep.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

command -v python3 >/dev/null 2>&1 || { echo "cleanup-evidence: python3 is required" >&2; exit 1; }

exec python3 "${SCRIPT_DIR}/lib/cleanup_evidence.py" "$@"
