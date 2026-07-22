"""Single source of truth for Performance Lab evidence artifact classes.

Used by audit-evidence-storage.sh and cleanup-evidence.sh (both invoke this
module as a library, never duplicate these patterns). Retention policy is
driven by class, never by ad hoc filename accidents scattered across
scripts (docs/evidence-storage-retention.md).

Classes (checked in this priority order — most specific first):
  build-output  Maven/Cargo build products. Never touched by retention
                (already gitignored; not evidence at all).
  raw-profiler  perf.data / perf-c2c.data / JFR recordings. The default
                retention policy deletes these once a bounded summary
                report has been generated and validated.
  temporary     Intermediate extraction dirs, in-progress/partial output.
                Safe to delete any time; never canonical.
  diagnostic    Thread dumps, timeout diagnostics, bounded console logs,
                failure summaries. Kept for failed/partial runs, bounded.
  canonical     environment/manifest/JMH-json/perf-stat-CSV/worker-
                placement/hashes/provenance/the bounded summarized
                profiler report. Never deleted by any cleanup policy.
  unclassified  Anything not matched above — surfaced by the audit report
                rather than silently bucketed as canonical or discarded.
"""
import re

BUILD_OUTPUT_PATTERNS = [
    r"(^|/)target/",
    r"(^|/)build/",
    r"(^|/)node_modules/",
    r"benchmarks\.jar$",
]

RAW_PROFILER_PATTERNS = [
    r"(^|/)perf\.data(\.old)?$",
    r"(^|/)perf-c2c\.data$",
    r"\.jfr$",
    r"(^|/)\.perf-c2c-report-raw\.txt$",
]

TEMPORARY_PATTERNS = [
    r"\.tmp$",
    r"\.partial$",
    r"(^|/)\.performance-lab-.*\.tar\.(gz|zst)\.tmp$",
    r"(^|/)tmp-",
    r"(^|/)\.extract-",
]

DIAGNOSTIC_PATTERNS = [
    r"(^|/)timeout-.*\.txt$",
    r"(^|/)timeout-diagnostics\.json$",
    r"(^|/)timeout-jcmd-.*\.txt$",
    r"(^|/)timeout-affinity-.*\.txt$",
    r"(^|/)timeout-process-tree\.txt$",
    r"-console\.log$",
    r"\.console\.log$",
    r"\.verify\.log$",
    r"(^|/)rejected\.txt$",
    r"perf-c2c-console\.log$",
    r"perf-c2c-report\.log$",
    r"(aux|rust)-evidence\.log$",
    r"(^|/)correctness-console\.log$",
]

CANONICAL_PATTERNS = [
    r"(^|/)environment\.json$",
    r"(^|/)evidence-manifest\.json$",
    r"(^|/)toolchain\.json$",
    r"(^|/)capabilities\.json$",
    r"(^|/)benchmark-profile\.json$",
    r"(^|/)correctness\.json$",
    r"(^|/)run-status\.json$",
    r"(^|/)topology\.txt$",
    r"(^|/)jmh\.json$",
    r"perf-stat.*\.csv$",
    r"perf-stat-jmh.*\.json$",
    r"(^|/)worker-placement.*\.json$",
    r"(^|/)placement-policy\.json$",
    r"(^|/)jmh-placement\.csv$",
    r"(^|/)perf-c2c-report\.txt$",
    r"(^|/)rust-evidence\.json$",
    r"(^|/)aux-evidence\.json$",
    r"(^|/)batch-manifest\.json$",
    r"(^|/)host-config\.resolved\.(json|yaml)$",
    r"(^|/)source-revision\.json$",
    r"(^|/)host-stability-samples\.jsonl$",
    r"(^|/)raw-profiler-retention\.json$",
    r"(^|/)storage-progress\.jsonl$",
    r"(^|/)filesystem-preflight\.json$",
    r"(^|/)storage-preflight\.json$",
    r"(^|/)canonical-.*\.json$",
    r"(^|/)comparison\.json$",
    r"(^|/)SHA256SUMS$",
    r"host-environment/(before|after)-batch\.json$",
]

_COMPILED = None


def _compiled():
    global _COMPILED
    if _COMPILED is None:
        _COMPILED = {
            "build-output": [re.compile(p) for p in BUILD_OUTPUT_PATTERNS],
            "raw-profiler": [re.compile(p) for p in RAW_PROFILER_PATTERNS],
            "temporary": [re.compile(p) for p in TEMPORARY_PATTERNS],
            "diagnostic": [re.compile(p) for p in DIAGNOSTIC_PATTERNS],
            "canonical": [re.compile(p) for p in CANONICAL_PATTERNS],
        }
    return _COMPILED


def classify(relpath: str) -> str:
    """Classify one file by its path relative to the evidence output root."""
    normalized = relpath.replace("\\", "/")
    for cls in ("build-output", "raw-profiler", "temporary", "diagnostic", "canonical"):
        for pattern in _compiled()[cls]:
            if pattern.search(normalized):
                return cls
    return "unclassified"


# Retention policies a cleanup pass may select with --policy. Each maps to
# the set of evidence classes it is allowed to remove. "keep-canonical" is
# a safety alias for "everything except canonical" — the same effective set
# as raw-profiler-only ∪ temporary ∪ diagnostic, spelled out explicitly so a
# future class addition is not silently swept in.
DELETABLE_CLASSES_BY_POLICY = {
    "raw-profiler-only": {"raw-profiler"},
    "keep-canonical": {"raw-profiler", "temporary", "diagnostic"},
}

NEVER_DELETE_CLASSES = {"canonical"}
