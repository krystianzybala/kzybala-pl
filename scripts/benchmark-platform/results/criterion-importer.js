// Criterion result importer (plab-003 task 3; rewritten in the 2026-07-14
// remediation pass). The v1 version read only "median" and "mean" (falling
// back mean->median), never read "slope" at all, and collapsed whichever
// one won into a single generic `score` — the audit's central Criterion
// finding: mean/median/slope were not kept as three distinct values. This
// version keeps all three independently, each with its own error/CI, plus
// outlier metadata, throughput mode, and baseline/comparison metadata
// (caller-supplied, since none of that lives in estimates.json itself).
import { SCHEMA_ID, SCHEMA_VERSION, STATISTIC_KINDS } from "./schema.js";
import { toCanonicalNumber, NumericError } from "./numeric.js";
import { buildProvenance, buildEvidence } from "./record-builder.js";
import { IMPORTER_CAPABILITIES } from "./capability-registry.js";

const IMPORTER_NAME = "criterion-importer";
const IMPORTER_VERSION = 1;
const IMPORTER_CAPABILITY = IMPORTER_CAPABILITIES[IMPORTER_NAME];
const CRITERION_NATIVE_UNIT = "ns";

// Criterion's estimates.json has one of these per statistic
// ({ point_estimate, standard_error, confidence_interval: { confidence_level,
// lower_bound, upper_bound } }); reads exactly the one the caller asks for,
// independently of any other estimate — no estimate is preferred over or
// substituted for another here.
function readEstimate(estimates, name) {
  const estimate = estimates?.[name];
  if (!estimate || typeof estimate.point_estimate !== "number") return null;

  const ci = estimate.confidence_interval;
  let error = null;
  let errorKind = null;
  let confidenceLevel = null;
  if (ci && typeof ci.lower_bound === "number" && typeof ci.upper_bound === "number") {
    if (ci.lower_bound > ci.upper_bound) {
      throw new Error(`Criterion import: "${name}".confidence_interval has lower_bound (${ci.lower_bound}) > upper_bound (${ci.upper_bound}) — contradictory input`);
    }
    error = toCanonicalNumber((ci.upper_bound - ci.lower_bound) / 2);
    errorKind = "confidence-interval";
    confidenceLevel = typeof ci.confidence_level === "number" ? toCanonicalNumber(ci.confidence_level) : null;
  } else if (typeof estimate.standard_error === "number") {
    error = toCanonicalNumber(estimate.standard_error);
    errorKind = "stddev";
  }

  return { pointEstimate: toCanonicalNumber(estimate.point_estimate), error, errorKind, confidenceLevel };
}

function buildOutliers(outliers) {
  if (!outliers || typeof outliers !== "object") return null;
  const { lowSevere = 0, lowMild = 0, highMild = 0, highSevere = 0, effect = null } = outliers;
  if (![lowSevere, lowMild, highMild, highSevere].every((n) => Number.isInteger(n) && n >= 0)) {
    throw new Error("Criterion import: meta.outliers counts must be non-negative integers");
  }
  return { lowSevere, lowMild, highMild, highSevere, effect };
}

// One Criterion estimates.json document -> one canonical scalar record.
// `estimates` is Criterion's own JSON (target/criterion/<bench>/<fn>/new/estimates.json);
// `meta` supplies everything that format doesn't carry (labId, variant,
// throughput mode, toolchain versions, provenance/evidence).
function importCriterionEstimates(estimates, meta) {
  if (!estimates || typeof estimates !== "object" || Array.isArray(estimates)) {
    throw new Error("Criterion import: estimates.json document must be an object");
  }
  if (!meta || !meta.labId || !meta.variant) {
    throw new Error("Criterion import requires meta.labId and meta.variant");
  }

  let mean;
  let median;
  let slope;
  try {
    mean = readEstimate(estimates, "mean");
    median = readEstimate(estimates, "median");
    slope = readEstimate(estimates, "slope");
  } catch (err) {
    if (err instanceof NumericError) throw new Error(`Criterion import ("${meta.variant}"): ${err.message}`);
    throw err;
  }

  if (!mean && !median && !slope) {
    throw new Error(`Criterion import ("${meta.variant}"): estimates.json has no usable "mean", "median" or "slope" estimate`);
  }

  // Criterion's own CLI headline is the bootstrap-resampled median; that
  // remains the default `primary` pick when present, purely for table/chart
  // display — mean and slope are retained in full alongside it, not
  // discarded.
  const primary = median ? "median" : mean ? "mean" : "slope";

  const isThroughput = meta.throughput && typeof meta.throughput.unit === "string";
  const unit = isThroughput ? meta.throughput.unit : CRITERION_NATIVE_UNIT;
  const direction = isThroughput ? "higherIsBetter" : "lowerIsBetter";

  const statistic = {
    primary,
    estimates: { mean, median, slope },
    sampleCount: typeof meta.sampleCount === "number" ? toCanonicalNumber(meta.sampleCount) : null,
    percentiles: null,
    rawSamples: null,
    outliers: buildOutliers(meta.outliers),
    secondaryMetrics: null,
    derivedFrom: null,
  };

  if (!STATISTIC_KINDS.includes(primary)) {
    throw new Error(`Criterion import ("${meta.variant}"): unreachable — primary "${primary}" is not a known statistic kind`);
  }

  const record = {
    schemaId: SCHEMA_ID,
    schemaVersion: SCHEMA_VERSION,
    labId: meta.labId,
    variant: meta.variant,
    language: "rust",
    harness: "criterion",
    metricKind: "scalar",
    unit,
    direction,
    mode: null,
    parameters: meta.parameters && typeof meta.parameters === "object" ? { ...meta.parameters } : {},
    statistic,
    provenance: buildProvenance(statistic, meta, `${IMPORTER_NAME}@${IMPORTER_VERSION}`),
    evidence: buildEvidence(meta, IMPORTER_CAPABILITY),
    toolchain: {
      criterionVersion: meta.criterionVersion ?? null,
      rustcVersion: meta.rustcVersion ?? null,
      targetTriple: meta.targetTriple ?? null,
    },
    comparability: {
      buildMode: "release",
      threads: meta.threads ?? null,
      forks: null,
      warmup: meta.warmup ?? null,
      measurement: null,
      datasetId: meta.datasetId ?? null,
      semanticsFixtureHash: meta.semanticsFixtureHash ?? null,
      architecture: meta.architecture ?? null,
    },
  };
  if (meta.baseline || meta.comparison) {
    record.notes = [
      ...(meta.notes ?? []),
      ...(meta.baseline ? [`Baseline: ${JSON.stringify(meta.baseline)}`] : []),
      ...(meta.comparison ? [`Comparison: ${JSON.stringify(meta.comparison)}`] : []),
    ];
  } else if (meta.notes) {
    record.notes = meta.notes;
  }
  return record;
}

export { IMPORTER_NAME, IMPORTER_VERSION, IMPORTER_CAPABILITY, CRITERION_NATIVE_UNIT, importCriterionEstimates };
