#!/usr/bin/env python3
"""Safe retention cleanup for the Performance Lab evidence tree
(docs/evidence-storage-retention.md). Invoked via
scripts/performance-lab/cleanup-evidence.sh. Defaults to --dry-run;
deletion requires an explicit --apply. Canonical evidence and anything
outside the results root (source files) are never candidates — this
module never receives a path outside the results root and never deletes
a file classified "canonical" regardless of policy.
"""
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from evidence_classify import classify, DELETABLE_CLASSES_BY_POLICY  # noqa: E402

RUN_ID_RE = re.compile(r"^linux-\d{8}T\d{6}Z$")
BATCH_ID_RE = re.compile(r"^batch-\d{8}T\d{6}Z$")

POLICIES = ("failed-and-partial", "raw-profiler-only", "older-than", "keep-canonical")

# Classes each policy is permitted to delete. "canonical" is never in any
# set — enforced again defensively at delete time, not just here.
POLICY_DELETABLE_CLASSES = {
    "raw-profiler-only": {"raw-profiler"},
    "keep-canonical": {"raw-profiler", "temporary", "diagnostic"},
    "failed-and-partial": {"raw-profiler", "temporary"},
    "older-than": {"raw-profiler", "temporary"},
}


def _batch_states(results_root: str) -> dict:
    """batch-dir-name -> state, from each batch-manifest.json."""
    states = {}
    batches_dir = os.path.join(results_root, "batches")
    if os.path.isdir(batches_dir):
        for entry in os.listdir(batches_dir):
            batch_dir = os.path.join(batches_dir, entry)
            if not os.path.isdir(batch_dir) or not BATCH_ID_RE.match(entry):
                continue
            manifest = os.path.join(batch_dir, "batch-manifest.json")
            state = "unknown"
            if os.path.isfile(manifest):
                try:
                    with open(manifest) as fh:
                        state = json.load(fh).get("state", "unknown")
                except (OSError, ValueError):
                    state = "unreadable-manifest"
            states[entry] = state
    return states


def _run_status(run_dir: str) -> str:
    """runStatus from <run_dir>/run-status.json, 'unknown' if absent."""
    path = os.path.join(run_dir, "run-status.json")
    if os.path.isfile(path):
        try:
            with open(path) as fh:
                return json.load(fh).get("runStatus", "unknown")
        except (OSError, ValueError):
            return "unreadable-manifest"
    return "unknown"


def _is_failed_or_partial_scope(relpath: str, results_root: str, batch_states: dict) -> bool:
    """True when relpath sits under a failed/partial batch, under
    failed-runs/, or under a standalone run whose run-status is not
    'collected'."""
    parts = relpath.split(os.sep)
    if parts and parts[0] == "batches" and len(parts) > 1:
        batch_id = parts[1]
        state = batch_states.get(batch_id, "unknown")
        if state not in ("complete",):
            return True
        # even a "complete" batch can contain individually failed runs,
        # preserved verbatim under failed-runs/
        if len(parts) > 2 and parts[2] == "failed-runs":
            return True
        return False
    # standalone lab run: results/<lab>/<run-id>/... — find the nearest
    # ancestor matching the RUN_ID scheme and read its run-status.json.
    cur = results_root
    for part in parts:
        cur = os.path.join(cur, part)
        if RUN_ID_RE.match(part):
            return _run_status(cur) != "collected"
    return False


def plan_cleanup(results_root: str, policy: str, older_than_days: float = None) -> dict:
    results_root = os.path.abspath(results_root)
    if policy not in POLICIES:
        raise ValueError(f"unknown policy '{policy}' (expected one of {POLICIES})")
    if policy == "older-than" and older_than_days is None:
        raise ValueError("policy 'older-than' requires --older-than-days")

    deletable_classes = POLICY_DELETABLE_CLASSES[policy]
    batch_states = _batch_states(results_root) if policy == "failed-and-partial" else {}
    now = time.time()
    cutoff = now - (older_than_days * 86400) if older_than_days is not None else None

    selected = []
    canonical_preserved = 0
    batches_affected = set()
    bytes_to_recover = 0

    if not os.path.isdir(results_root):
        return {
            "resultsRoot": results_root,
            "policy": policy,
            "olderThanDays": older_than_days,
            "filesSelected": [],
            "fileCount": 0,
            "bytesToRecover": 0,
            "batchesAffected": [],
            "canonicalFilesPreserved": 0,
        }

    for root, _dirs, files in os.walk(results_root):
        for name in files:
            fp = os.path.join(root, name)
            relpath = os.path.relpath(fp, results_root)
            cls = classify(relpath)
            if cls == "canonical":
                canonical_preserved += 1
                continue
            if cls not in deletable_classes:
                continue
            try:
                st = os.lstat(fp)
            except OSError:
                continue

            if policy == "failed-and-partial":
                if not _is_failed_or_partial_scope(relpath, results_root, batch_states):
                    continue
            elif policy == "older-than":
                if st.st_mtime >= cutoff:
                    continue

            selected.append({"path": relpath, "bytes": st.st_size, "class": cls})
            bytes_to_recover += st.st_size
            parts = relpath.split(os.sep)
            if parts and parts[0] == "batches" and len(parts) > 1:
                batches_affected.add(parts[1])

    return {
        "resultsRoot": results_root,
        "policy": policy,
        "olderThanDays": older_than_days,
        "filesSelected": selected,
        "fileCount": len(selected),
        "bytesToRecover": bytes_to_recover,
        "batchesAffected": sorted(batches_affected),
        "canonicalFilesPreserved": canonical_preserved,
    }


def apply_cleanup(plan: dict) -> dict:
    """Deletes exactly the files in plan['filesSelected']. Refuses (raises)
    if any selected file is classified canonical — defense in depth even
    though plan_cleanup never selects canonical files."""
    root = plan["resultsRoot"]
    removed, missing = 0, 0
    for entry in plan["filesSelected"]:
        if classify(entry["path"]) == "canonical":
            raise RuntimeError(f"refusing to delete canonical file: {entry['path']}")
        fp = os.path.join(root, entry["path"])
        try:
            os.remove(fp)
            removed += 1
        except FileNotFoundError:
            missing += 1
    return {"removed": removed, "alreadyMissing": missing}


def human_bytes(n: int) -> str:
    n = float(n)
    for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
        if abs(n) < 1024.0:
            return f"{n:3.1f} {unit}"
        n /= 1024.0
    return f"{n:.1f} PiB"


def print_report(plan: dict, applied: dict = None) -> None:
    print(f"Cleanup plan: {plan['resultsRoot']}  policy={plan['policy']}"
          + (f"  olderThanDays={plan['olderThanDays']}" if plan.get("olderThanDays") is not None else ""))
    print(f"  files selected:            {plan['fileCount']}")
    print(f"  bytes to recover:          {human_bytes(plan['bytesToRecover'])} ({plan['bytesToRecover']} bytes)")
    print(f"  batches affected:          {len(plan['batchesAffected'])} {plan['batchesAffected']}")
    print(f"  canonical files preserved: {plan['canonicalFilesPreserved']}")
    if applied is None:
        print()
        print("  DRY RUN — nothing deleted. Re-run with --apply to delete the files above.")
    else:
        print()
        print(f"  APPLIED — removed {applied['removed']} file(s) ({applied['alreadyMissing']} already missing).")


def main(argv: list) -> int:
    results_root = None
    policy = None
    older_than_days = None
    apply = False
    as_json = False
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--results-root":
            results_root = argv[i + 1]; i += 2
        elif a == "--policy":
            policy = argv[i + 1]; i += 2
        elif a == "--older-than-days":
            older_than_days = float(argv[i + 1]); i += 2
        elif a == "--apply":
            apply = True; i += 1
        elif a == "--dry-run":
            apply = False; i += 1
        elif a == "--json":
            as_json = True; i += 1
        else:
            print(f"cleanup-evidence: unknown option {a}", file=sys.stderr)
            return 2

    if not results_root or not policy:
        print("usage: cleanup-evidence.sh --results-root <dir> --policy <policy> [--older-than-days N] [--dry-run|--apply] [--json]", file=sys.stderr)
        print(f"policies: {', '.join(POLICIES)}", file=sys.stderr)
        return 2

    try:
        plan = plan_cleanup(results_root, policy, older_than_days)
    except ValueError as exc:
        print(f"cleanup-evidence: {exc}", file=sys.stderr)
        return 2

    applied = None
    if apply:
        applied = apply_cleanup(plan)

    if as_json:
        out = dict(plan)
        if applied is not None:
            out["applied"] = applied
        out["dryRun"] = not apply
        print(json.dumps(out, indent=2))
    else:
        print_report(plan, applied)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
