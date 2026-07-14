// Latency histogram importer (plab-003 task 4; rewritten in the 2026-07-14
// remediation pass). No lab in this repository emits a histogram today
// (docs/benchmark-platform-inventory.md) — this exists because the
// canonical schema explicitly names "latency histograms" as a result kind
// future laboratory changes must be able to publish through. This version
// adds the fields the audit found missing: format/implementation identity,
// significant digits, overflow/saturation metadata, and explicit
// coordinated-omission-correction status (previously entirely unmodeled).
import { SCHEMA_ID, SCHEMA_VERSION } from "./schema.js";
import { toCanonicalNumber } from "./numeric.js";
import { buildProvenance, buildEvidence } from "./record-builder.js";
import { IMPORTER_CAPABILITIES } from "./capability-registry.js";

const IMPORTER_NAME = "histogram-importer";
const IMPORTER_VERSION = 2;
const IMPORTER_CAPABILITY = IMPORTER_CAPABILITIES[IMPORTER_NAME];
const REQUIRED_PERCENTILES = ["p50", "p90", "p99"];

// `source` shape: { format, count, min, max, mean, unit, percentiles: {
// p50, p90, p99, p999?, ... }, significantDigits?, overflow?: { saturated,
// saturatedCount? }, coordinatedOmission?: { corrected, method? } }. Fails
// fast on a missing required percentile or an unidentified format rather
// than silently reporting an incomplete tail.
function importHistogram(source, meta) {
  if (!meta || !meta.labId || !meta.variant) {
    throw new Error("Histogram import requires meta.labId and meta.variant");
  }
  if (!source || typeof source !== "object") {
    throw new Error("Histogram import requires a source object");
  }
  if (typeof source.format !== "string" || source.format.length === 0) {
    throw new Error(`Histogram import ("${meta.variant}"): source.format is required (e.g. "hdrhistogram", "generic-percentile-map") — an unidentified histogram implementation cannot be trusted for tail-latency claims`);
  }
  const missingPercentiles = REQUIRED_PERCENTILES.filter((p) => typeof source.percentiles?.[p] !== "number");
  if (missingPercentiles.length > 0) {
    throw new Error(`Histogram import ("${meta.variant}"): missing required percentile(s): ${missingPercentiles.join(", ")}`);
  }
  for (const field of ["count", "min", "max", "mean"]) {
    if (typeof source[field] !== "number") {
      throw new Error(`Histogram import ("${meta.variant}"): missing required field "${field}"`);
    }
  }
  if (typeof source.unit !== "string" || source.unit.length === 0) {
    throw new Error(`Histogram import ("${meta.variant}"): source.unit is required`);
  }

  const percentiles = {};
  for (const [key, value] of Object.entries(source.percentiles)) {
    if (typeof value !== "number") throw new Error(`Histogram import ("${meta.variant}"): percentiles.${key} is not a number`);
    percentiles[key] = toCanonicalNumber(value);
  }

  const statistic = {
    format: source.format,
    count: toCanonicalNumber(source.count),
    min: toCanonicalNumber(source.min),
    max: toCanonicalNumber(source.max),
    mean: toCanonicalNumber(source.mean),
    percentiles,
    significantDigits: Number.isInteger(source.significantDigits) ? source.significantDigits : null,
    overflow: source.overflow && typeof source.overflow.saturated === "boolean"
      ? { saturated: source.overflow.saturated, saturatedCount: typeof source.overflow.saturatedCount === "number" ? toCanonicalNumber(source.overflow.saturatedCount) : null }
      : null,
    coordinatedOmission: source.coordinatedOmission && typeof source.coordinatedOmission.corrected === "boolean"
      ? { corrected: source.coordinatedOmission.corrected, method: typeof source.coordinatedOmission.method === "string" ? source.coordinatedOmission.method : null }
      : null,
  };

  return {
    schemaId: SCHEMA_ID,
    schemaVersion: SCHEMA_VERSION,
    labId: meta.labId,
    variant: meta.variant,
    language: meta.language ?? null,
    harness: "histogram",
    metricKind: "histogram",
    unit: source.unit,
    direction: null,
    mode: null,
    parameters: meta.parameters && typeof meta.parameters === "object" ? { ...meta.parameters } : {},
    statistic,
    provenance: buildProvenance(statistic, meta, `${IMPORTER_NAME}@${IMPORTER_VERSION}`),
    evidence: buildEvidence(meta, IMPORTER_CAPABILITY),
    comparability: {
      buildMode: meta.buildMode ?? null,
      threads: meta.threads ?? null,
      forks: null,
      warmup: meta.warmup ?? null,
      measurement: null,
      datasetId: meta.datasetId ?? null,
      semanticsFixtureHash: meta.semanticsFixtureHash ?? null,
      architecture: meta.architecture ?? null,
    },
  };
}

export { IMPORTER_NAME, IMPORTER_VERSION, IMPORTER_CAPABILITY, REQUIRED_PERCENTILES, importHistogram };
