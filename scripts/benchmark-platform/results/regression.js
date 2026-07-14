// Historical-run comparison and regression thresholds (plab-003 task 8).
// design.md's "Regression policy": "Compare only matching lab, variant,
// dataset, parameters, architecture class and compatible environment
// profiles. Thresholds may differ by metric." Only records with
// evidenceMaturity "verified" are eligible history — an unreviewed draft
// run is not a legitimate baseline to regress against (design.md's review
// workflow).
const DEFAULT_RELATIVE_THRESHOLD = 0.05; // 5% — a documented default, not a guess per lab.

// Two records are "compatible" for regression comparison when every field
// the audit calls out actually matches: lab, variant, language, harness
// (== "parameters" for JMH/Criterion — same benchmark method, same profile
// shape), dataset, build mode, architecture class, and environment profile
// (approximated here by the environment capture reference itself: two runs
// with different provenance.environmentRef were not proven to have run on
// comparably-configured hosts, so they are not silently treated as
// interchangeable).
function isCompatible(a, b) {
  return (
    a.labId === b.labId &&
    a.variant === b.variant &&
    a.language === b.language &&
    a.harness === b.harness &&
    (a.comparability?.datasetId ?? null) === (b.comparability?.datasetId ?? null) &&
    (a.comparability?.buildMode ?? null) === (b.comparability?.buildMode ?? null) &&
    (a.comparability?.architecture ?? null) === (b.comparability?.architecture ?? null) &&
    (a.provenance?.environmentRef ?? null) === (b.provenance?.environmentRef ?? null)
  );
}

// Picks the most recent compatible, verified historical record as the
// baseline — "most recent" by provenance.capturedAt (ISO string, so plain
// string comparison sorts correctly), never by array position, so caller
// ordering can't silently change which run is the baseline.
function findBaseline(newRecord, history) {
  const compatible = history.filter((candidate) => candidate.evidenceMaturity === "verified" && isCompatible(newRecord, candidate));
  if (compatible.length === 0) return null;
  return compatible.reduce((latest, candidate) => {
    if (!latest) return candidate;
    return (candidate.provenance.capturedAt ?? "") > (latest.provenance.capturedAt ?? "") ? candidate : latest;
  }, null);
}

function relativeDelta(newValue, baselineValue) {
  if (baselineValue === 0) return newValue === 0 ? 0 : Infinity;
  return (newValue - baselineValue) / Math.abs(baselineValue);
}

// Compares `newRecord` against compatible `history`, using a per-unit
// threshold override from `thresholds` (e.g. { "ops/ms": 0.03 }) falling
// back to DEFAULT_RELATIVE_THRESHOLD. Returns one of:
//   "insufficient-history" — no compatible verified baseline exists yet.
//   "regression"           — moved the wrong direction beyond threshold.
//   "improvement"          — moved the right direction beyond threshold.
//   "stable"               — within threshold either way.
// Never silences a regression by rounding it away — `delta` is the exact
// relative change, unrounded.
function compareToHistory(newRecord, history, thresholds = {}) {
  if (newRecord.metricKind !== "scalar") {
    throw new Error(`compareToHistory only supports metricKind "scalar" (got "${newRecord.metricKind}")`);
  }
  if (!newRecord.direction) {
    throw new Error(`compareToHistory requires newRecord.direction ("higherIsBetter" or "lowerIsBetter")`);
  }

  const baseline = findBaseline(newRecord, history);
  if (!baseline) {
    return { status: "insufficient-history", baseline: null, delta: null, threshold: null };
  }

  const threshold = thresholds[newRecord.unit] ?? DEFAULT_RELATIVE_THRESHOLD;
  const delta = relativeDelta(newRecord.statistic.pointEstimate, baseline.statistic.pointEstimate);
  const worseDirectionDelta = newRecord.direction === "higherIsBetter" ? -delta : delta;

  let status;
  if (worseDirectionDelta > threshold) {
    status = "regression";
  } else if (worseDirectionDelta < -threshold) {
    status = "improvement";
  } else {
    status = "stable";
  }

  return { status, baseline, delta, threshold };
}

export { DEFAULT_RELATIVE_THRESHOLD, isCompatible, findBaseline, compareToHistory };
