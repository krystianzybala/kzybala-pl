#!/usr/bin/env python3
"""Deterministic storage inventory for the Performance Lab evidence tree
(docs/evidence-storage-retention.md). Invoked via
scripts/performance-lab/audit-evidence-storage.sh — never run directly by
a human as the primary entry point, so all flag parsing lives in the
wrapper; this module takes already-parsed arguments.

Single walk over the results root. Never writes a large file itself: the
top-N-largest-files list is bounded (heapq.nlargest), and the JSON report
is O(labs + variants + batches), never O(files).
"""
import heapq
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from evidence_classify import classify  # noqa: E402

RUN_ID_RE = re.compile(r"^linux-\d{8}T\d{6}Z$")
BATCH_ID_RE = re.compile(r"^batch-\d{8}T\d{6}Z$")


def build_report(results_root: str, top_n: int = 50) -> dict:
    results_root = os.path.abspath(results_root)
    by_class = {"canonical": 0, "diagnostic": 0, "raw-profiler": 0, "temporary": 0, "build-output": 0, "unclassified": 0}
    per_lab = {}
    per_variant = {}
    per_batch = {}
    total_bytes = 0
    file_count = 0
    largest = []  # heap of (size, path) via heapq.nlargest at the end

    if not os.path.isdir(results_root):
        return {
            "resultsRoot": results_root,
            "exists": False,
            "totalBytes": 0,
            "byClass": by_class,
            "perLab": {},
            "perVariant": {},
            "perBatch": {},
            "byBatchState": {},
            "rawProfilerBytes": 0,
            "canonicalBytes": 0,
            "fileCount": 0,
            "top50Files": [],
        }

    all_files = []  # (relpath, size, cls)
    for root, _dirs, files in os.walk(results_root):
        for name in files:
            fp = os.path.join(root, name)
            try:
                size = os.lstat(fp).st_size
            except OSError:
                continue
            relpath = os.path.relpath(fp, results_root)
            cls = classify(relpath)
            all_files.append((relpath, size, cls))
            total_bytes += size
            file_count += 1
            by_class[cls] = by_class.get(cls, 0) + size
            largest.append((size, relpath))

    # --- per-lab / per-variant, via unambiguous RUN_ID directory markers ---
    # A "run directory" is any directory named linux-<UTC timestamp>Z (the
    # RUN_ID scheme in run-linux-evidence.sh); its parent directory name is
    # the lab id, and its immediate children are variant directories. This
    # detection works identically for standalone runs (results/<lab>/<run>)
    # and batch runs (results/batches/<batch>/run-N/<lab>/<run>, and failed
    # runs under results/batches/<batch>/failed-runs/<lab>-run-N/<lab>/<run>).
    for root, dirs, _files in os.walk(results_root):
        for d in dirs:
            if RUN_ID_RE.match(d):
                run_dir = os.path.join(root, d)
                lab_id = os.path.basename(root)
                lab_bytes = dir_size(run_dir)
                per_lab[lab_id] = per_lab.get(lab_id, 0) + lab_bytes
                try:
                    variant_names = [
                        v for v in os.listdir(run_dir) if os.path.isdir(os.path.join(run_dir, v))
                    ]
                except OSError:
                    variant_names = []
                for v in variant_names:
                    key = f"{lab_id}/{v}"
                    per_variant[key] = per_variant.get(key, 0) + dir_size(os.path.join(run_dir, v))

    # --- per-batch, via unambiguous BATCH_ID directory markers -------------
    batches_dir = os.path.join(results_root, "batches")
    by_batch_state = {}
    if os.path.isdir(batches_dir):
        for entry in sorted(os.listdir(batches_dir)):
            batch_dir = os.path.join(batches_dir, entry)
            if not os.path.isdir(batch_dir) or not BATCH_ID_RE.match(entry):
                continue
            batch_bytes = dir_size(batch_dir)
            state = "unknown"
            manifest_path = os.path.join(batch_dir, "batch-manifest.json")
            if os.path.isfile(manifest_path):
                try:
                    with open(manifest_path) as fh:
                        state = json.load(fh).get("state", "unknown")
                except (OSError, ValueError):
                    state = "unreadable-manifest"
            per_batch[entry] = {"bytes": batch_bytes, "state": state}
            by_batch_state[state] = by_batch_state.get(state, 0) + batch_bytes

    top_files = [
        {"path": p, "bytes": s, "class": classify(p)}
        for s, p in heapq.nlargest(top_n, largest)
    ]

    raw_bytes = by_class.get("raw-profiler", 0)
    canonical_bytes = by_class.get("canonical", 0)

    return {
        "resultsRoot": results_root,
        "exists": True,
        "totalBytes": total_bytes,
        "fileCount": file_count,
        "byClass": by_class,
        "rawProfilerBytes": raw_bytes,
        "canonicalBytes": canonical_bytes,
        "rawToCanonicalRatio": round(raw_bytes / canonical_bytes, 2) if canonical_bytes else (None if raw_bytes == 0 else "infinite"),
        "perLab": per_lab,
        "perVariant": per_variant,
        "perBatch": per_batch,
        "byBatchState": by_batch_state,
        f"top{top_n}Files": top_files,
    }


def dir_size(path: str) -> int:
    total = 0
    for root, _dirs, files in os.walk(path):
        for name in files:
            fp = os.path.join(root, name)
            try:
                total += os.lstat(fp).st_size
            except OSError:
                pass
    return total


def human_bytes(n: int) -> str:
    n = float(n)
    for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
        if abs(n) < 1024.0:
            return f"{n:3.1f} {unit}"
        n /= 1024.0
    return f"{n:.1f} PiB"


def print_human(report: dict) -> None:
    if not report["exists"]:
        print(f"Storage audit: {report['resultsRoot']} does not exist — nothing to report.")
        return
    print(f"Storage audit: {report['resultsRoot']}")
    print(f"  total: {human_bytes(report['totalBytes'])} across {report['fileCount']} file(s)")
    print()
    print("  by class:")
    for cls, size in sorted(report["byClass"].items(), key=lambda kv: -kv[1]):
        print(f"    {cls:14s} {human_bytes(size):>12s}")
    print()
    ratio = report["rawToCanonicalRatio"]
    ratio_str = f"{ratio}x" if isinstance(ratio, (int, float)) else str(ratio)
    print(f"  raw-profiler bytes: {human_bytes(report['rawProfilerBytes'])} vs canonical: {human_bytes(report['canonicalBytes'])} (ratio {ratio_str})")
    print()
    if report["perBatch"]:
        print("  per batch:")
        for batch_id, info in sorted(report["perBatch"].items()):
            print(f"    {batch_id:28s} {human_bytes(info['bytes']):>12s}  state={info['state']}")
        print()
        print("  by batch state:")
        for state, size in sorted(report["byBatchState"].items(), key=lambda kv: -kv[1]):
            print(f"    {state:20s} {human_bytes(size):>12s}")
        print()
    if report["perLab"]:
        print("  per lab:")
        for lab_id, size in sorted(report["perLab"].items(), key=lambda kv: -kv[1]):
            print(f"    {lab_id:28s} {human_bytes(size):>12s}")
        print()
    if report["perVariant"]:
        print("  per variant (top 20 by size):")
        for key, size in sorted(report["perVariant"].items(), key=lambda kv: -kv[1])[:20]:
            print(f"    {key:40s} {human_bytes(size):>12s}")
        print()
    top_key = next((k for k in report if k.startswith("top") and k.endswith("Files")), None)
    if top_key and report[top_key]:
        print(f"  {top_key} (largest first):")
        for entry in report[top_key][:50]:
            print(f"    {human_bytes(entry['bytes']):>12s}  [{entry['class']:12s}]  {entry['path']}")


def main(argv: list) -> int:
    if not argv:
        print("usage: audit-evidence-storage.sh <results-root> [--json] [--out <file>]", file=sys.stderr)
        return 2
    results_root = argv[0]
    as_json = "--json" in argv
    out_file = None
    if "--out" in argv:
        out_file = argv[argv.index("--out") + 1]

    report = build_report(results_root)

    if out_file:
        with open(out_file, "w") as fh:
            json.dump(report, fh, indent=2)
            fh.write("\n")

    if as_json:
        print(json.dumps(report, indent=2))
    else:
        print_human(report)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
