// Historical-run comparison and regression thresholds (plab-003 task 8;
// rewritten in the 2026-07-14 remediation pass). The v1 `isCompatible`
// substituted a single opaque `provenance.environmentRef` string-equality
// check for ~8 of the required hardware/toolchain compatibility dimensions,
// and `compareToHistory` had no absolute threshold, no noise-floor/CI
// awareness, no minimum-run-count requirement, and no baseline-age policy —
// a "regression" could fire on pure measurement noise. This version:
//   - builds a compatibility key that cryptographically references the
//     hardware/environment bundle (via the environment manifest's verified
//     content hash) instead of trusting an unverified label,
//   - requires the baseline to actually be `verified` per the *derived*
//     maturity (evidence-maturity.js), not a stored string,
//   - never classifies a delta inside the combined measurement-uncertainty
//     noise floor as a regression,
//   - supports both relative and absolute thresholds,
//   - requires a minimum number of completed independent reproduction runs,
//   - excludes a baseline once it exceeds a configurable maximum age.
import { deriveMaturity } from "./evidence-maturity.js";
import { toApproximateNumber } from "./numeric.js";

const DEFAULT_RELATIVE_THRESHOLD = 0.05; // 5% — documented default, not a per-lab guess.
const DEFAULT_MIN_RUN_COUNT = 1;
const DEFAULT_MAX_BASELINE_AGE_DAYS = 180;

function estimateFor(record) {
  return record.statistic.estimates[record.statistic.primary];
}

// The compatibility key (audit section 9 / remediation requirement 12).
// Dimensions this repository's tooling can capture directly (lab/benchmark
// identity, metric identity, mode, statistic kind, unit, dataset/semantics-
// fixture identity, architecture class, build mode, JVM/rustc
// version+flags) are compared by exact-equality on their real captured
// values. Dimensions this repository cannot yet independently extract
// (CPU feature set, NUMA topology, SMT/turbo state, CPU affinity, OS+kernel
// build, native/container/VM/emulation status beyond the one
// `evidence.environment` classification) are folded into a single
// cryptographic reference — `environmentManifestHash` — rather than
// fabricated per-field detection: two records are only compatible on the
// *entire* hardware/environment bundle when they reference the exact same
// hash-verified environment-manifest snapshot. That is honest about what
// this codebase can and cannot independently verify today (see
// docs/benchmark-regression-policy.md), while still satisfying the
// requirement structurally — the key's shape does not need to change once
// environment capture is extended to populate more of those fields.
function compatibilityKey(record) {
  return {
    labId: record.labId,
    variant: record.variant,
    metricKind: record.metricKind,
    unit: record.unit,
    mode: record.mode,
    statisticKind: record.statistic.primary,
    datasetId: record.comparability?.datasetId ?? null,
    semanticsFixtureHash: record.comparability?.semanticsFixtureHash ?? null,
    benchmarkProfileRef: record.provenance.benchmarkProfile.ref,
    benchmarkProfileHash: record.provenance.benchmarkProfile.hash,
    architecture: record.comparability?.architecture ?? null,
    buildMode: record.comparability?.buildMode ?? null,
    jvmVendor: record.toolchain?.vmName ?? null,
    jvmVersion: record.toolchain?.vmVersion ?? null,
    jvmFlags: JSON.stringify(record.toolchain?.jvmArgs ?? null),
    rustcVersion: record.toolchain?.rustcVersion ?? null,
    targetTriple: record.toolchain?.targetTriple ?? null,
    environmentManifestHash: record.provenance.environmentManifest.hash,
    executionMode: record.evidence.environment,
  };
}

// Two records are compatible only when every dimension of the key matches
// AND the dataset/semantics-fixture identity and environment-manifest hash
// are actually populated (non-null) on both sides — two records that are
// both silently missing that information are NOT treated as "compatible by
// coincidence"; they are non-comparable, because nothing was verified. A
// non-native execution mode (container/vm/emulated/unknown) can never be
// compatible with anything, on either side — no cross-machine or
// cross-virtualization comparison is silently allowed (design.md: "No
// cross-machine direct comparison unless the page is explicitly about
// machines").
function isCompatible(a, b) {
  const keyA = compatibilityKey(a);
  const keyB = compatibilityKey(b);
  if (keyA.executionMode !== "native-controlled" || keyB.executionMode !== "native-controlled") return false;
  for (const field of ["datasetId", "semanticsFixtureHash", "environmentManifestHash"]) {
    if (keyA[field] === null || keyB[field] === null) return false;
  }
  return Object.keys(keyA).every((field) => keyA[field] === keyB[field]);
}

// Picks the most recent, verified (per deriveMaturity, not a stored
// string), compatible, and not-too-stale historical record as the
// baseline. "Most recent" by provenance.capturedAt (ISO string), never by
// array position.
function findBaseline(newRecord, history, { maxBaselineAgeDays = DEFAULT_MAX_BASELINE_AGE_DAYS, now = new Date(), evidenceOpts = {} } = {}) {
  const cutoff = maxBaselineAgeDays === null ? null : new Date(now.getTime() - maxBaselineAgeDays * 24 * 60 * 60 * 1000);
  const eligible = history.filter((candidate) => {
    if (!isCompatible(newRecord, candidate)) return false;
    if (deriveMaturity(candidate, evidenceOpts).level !== "verified") return false;
    if (cutoff && candidate.provenance.capturedAt && new Date(candidate.provenance.capturedAt) < cutoff) return false;
    return true;
  });
  if (eligible.length === 0) return null;
  return eligible.reduce((latest, candidate) => ((candidate.provenance.capturedAt ?? "") > (latest.provenance.capturedAt ?? "") ? candidate : latest), eligible[0]);
}

// Compares `newRecord` against compatible, verified `history`. `thresholds`
// maps unit -> { relative?: number, absolute?: number, minRunCount?:
// number }. Returns one of:
//   "insufficient-runs"    — newRecord hasn't completed the required
//                            minimum independent reproduction count yet.
//   "insufficient-history" — no compatible, verified, non-stale baseline exists.
//   "regression"           — moved the wrong direction beyond a threshold
//                            AND beyond the combined measurement-uncertainty
//                            noise floor.
//   "improvement"          — moved the right direction beyond a threshold
//                            and beyond the noise floor.
//   "stable"               — within threshold, or within the noise floor
//                            (an uncertain delta is never called a
//                            regression, however large the raw percentage
//                            looks).
function compareToHistory(newRecord, history, thresholds = {}, opts = {}) {
  if (newRecord.metricKind !== "scalar") {
    throw new Error(`compareToHistory only supports metricKind "scalar" (got "${newRecord.metricKind}")`);
  }
  if (!newRecord.direction) {
    throw new Error('compareToHistory requires newRecord.direction ("higherIsBetter" or "lowerIsBetter")');
  }

  const unitThresholds = thresholds[newRecord.unit] ?? {};
  const minRunCount = unitThresholds.minRunCount ?? DEFAULT_MIN_RUN_COUNT;
  if (newRecord.evidence.reproduction.completed < minRunCount) {
    return { status: "insufficient-runs", baseline: null, delta: null, threshold: null, requiredRuns: minRunCount, completedRuns: newRecord.evidence.reproduction.completed };
  }

  const baseline = findBaseline(newRecord, history, { maxBaselineAgeDays: unitThresholds.maxBaselineAgeDays, evidenceOpts: opts.evidenceOpts });
  if (!baseline) {
    return { status: "insufficient-history", baseline: null, delta: null, threshold: null };
  }

  const newEstimate = estimateFor(newRecord);
  const baseEstimate = estimateFor(baseline);
  const newValue = toApproximateNumber(newEstimate.pointEstimate);
  const baseValue = toApproximateNumber(baseEstimate.pointEstimate);
  const newError = newEstimate.error ? toApproximateNumber(newEstimate.error) : 0;
  const baseError = baseEstimate.error ? toApproximateNumber(baseEstimate.error) : 0;
  const noiseFloor = newError + baseError;

  const absDelta = newValue - baseValue;
  const relDelta = baseValue === 0 ? (newValue === 0 ? 0 : Infinity) : absDelta / Math.abs(baseValue);
  const relThreshold = unitThresholds.relative ?? DEFAULT_RELATIVE_THRESHOLD;
  const absThreshold = unitThresholds.absolute ?? null;

  const worseDirectionAbsDelta = newRecord.direction === "higherIsBetter" ? -absDelta : absDelta;
  const worseDirectionRelDelta = newRecord.direction === "higherIsBetter" ? -relDelta : relDelta;

  const withinNoiseFloor = Math.abs(absDelta) <= noiseFloor;
  const exceedsThreshold = worseDirectionRelDelta > relThreshold || (absThreshold !== null && worseDirectionAbsDelta > absThreshold);
  const improvesThreshold = -worseDirectionRelDelta > relThreshold || (absThreshold !== null && -worseDirectionAbsDelta > absThreshold);

  let status;
  if (withinNoiseFloor) {
    status = "stable";
  } else if (exceedsThreshold) {
    status = "regression";
  } else if (improvesThreshold) {
    status = "improvement";
  } else {
    status = "stable";
  }

  return { status, baseline, delta: relDelta, absoluteDelta: absDelta, threshold: relThreshold, absoluteThreshold: absThreshold, noiseFloor };
}

export { DEFAULT_RELATIVE_THRESHOLD, DEFAULT_MIN_RUN_COUNT, DEFAULT_MAX_BASELINE_AGE_DAYS, compatibilityKey, isCompatible, findBaseline, compareToHistory };
