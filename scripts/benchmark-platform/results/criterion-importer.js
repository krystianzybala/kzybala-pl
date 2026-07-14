// Criterion result importer (plab-003 task 3).
// Parses Criterion's own `estimates.json` (written to
// `target/criterion/<bench>/<function>/new/estimates.json` — see
// docs/benchmark-artifact-layout.md and docs/benchmark-publication-procedure.md)
// into the canonical schema. Criterion reports all times in nanoseconds
// internally regardless of the unit it prints in its own CLI output; this
// importer preserves that and lets the renderer (task 6) choose display
// units, rather than baking a unit conversion into import.
import { SCHEMA_VERSION } from "./schema.js";

const IMPORTER_VERSION = 1;
const CRITERION_NATIVE_UNIT = "ns";

// Criterion's estimates.json has one of these per statistic
// ({ point_estimate, standard_error, confidence_interval: { confidence_level,
// lower_bound, upper_bound } }); this reads the one the caller asks for.
function readEstimate(estimates, name) {
  const estimate = estimates?.[name];
  if (!estimate || typeof estimate.point_estimate !== "number") {
    return null;
  }
  const ci = estimate.confidence_interval;
  const error =
    ci && typeof ci.lower_bound === "number" && typeof ci.upper_bound === "number"
      ? (ci.upper_bound - ci.lower_bound) / 2
      : typeof estimate.standard_error === "number"
        ? estimate.standard_error
        : null;
  return { pointEstimate: estimate.point_estimate, error, confidenceLevel: ci?.confidence_level ?? null };
}

// One Criterion estimates.json document -> one canonical scalar record.
// Criterion's headline statistic is the bootstrap-resampled **median**, not
// the mean (docs/benchmark-platform-inventory.md's cross-tool note) — this
// importer follows that convention rather than silently switching to mean.
function importCriterionEstimates(estimates, meta) {
  if (!meta || !meta.labId || !meta.variant) {
    throw new Error("Criterion import requires meta.labId and meta.variant");
  }
  const median = readEstimate(estimates, "median");
  const mean = readEstimate(estimates, "mean");
  if (!median && !mean) {
    throw new Error(`Criterion import ("${meta.variant}"): estimates.json has neither a usable "median" nor "mean"`);
  }
  const primary = median ?? mean;
  const statisticName = median ? "median" : "mean";

  return {
    schemaVersion: SCHEMA_VERSION,
    labId: meta.labId,
    variant: meta.variant,
    language: "rust",
    harness: "criterion",
    metricKind: "scalar",
    unit: CRITERION_NATIVE_UNIT,
    // Criterion's own estimates are always a time-per-iteration quantity —
    // lower is better, unconditionally (unlike JMH, Criterion has no
    // throughput-mode estimate in estimates.json).
    direction: "lowerIsBetter",
    statistic: {
      pointEstimate: primary.pointEstimate,
      error: primary.error,
      errorKind: primary.confidenceLevel ? `ci${primary.confidenceLevel}` : null,
      statisticName,
      sampleCount: meta.sampleCount ?? null,
    },
    provenance: {
      rawArtifactPath: meta.rawArtifactPath ?? null,
      sourceRevision: meta.sourceRevision ?? "unversioned",
      command: meta.command ?? null,
      environmentRef: meta.environmentRef ?? null,
      importerVersion: `criterion-importer@${IMPORTER_VERSION}`,
      capturedAt: meta.capturedAt ?? null,
    },
    comparability: {
      buildMode: "release",
      threads: meta.threads ?? null,
      forks: null,
      warmup: meta.warmup ?? null,
      datasetId: meta.datasetId ?? null,
      semanticsFixtureHash: meta.semanticsFixtureHash ?? null,
      architecture: meta.architecture ?? null,
    },
    evidenceMaturity: meta.evidenceMaturity ?? "draft",
    toolchain: {
      criterionVersion: meta.criterionVersion ?? null,
      rustcVersion: meta.rustcVersion ?? null,
      targetTriple: meta.targetTriple ?? null,
    },
  };
}

export { IMPORTER_VERSION, CRITERION_NATIVE_UNIT, importCriterionEstimates };
