// Latency histogram importer (plab-003 task 4).
// No lab in this repository emits a histogram today
// (docs/benchmark-platform-inventory.md) — this exists because design.md's
// canonical schema goal explicitly names "latency histograms" as a result
// kind future laboratory changes (e.g. plab-102 clocks/latency-histograms,
// plab-605 coordinated-omission-load-generation) must be able to publish
// through, without inventing their own ad hoc format later. The input shape
// is a generic percentile map — e.g. HdrHistogram's own
// `getValueAtPercentile` output, or an equivalent tool's export — not tied
// to one specific histogram library.
import { SCHEMA_VERSION } from "./schema.js";

const IMPORTER_VERSION = 1;
const REQUIRED_PERCENTILES = ["p50", "p90", "p99"];

// `source` shape: { count, min, max, mean, unit, percentiles: { p50, p90,
// p99, p999?, p9999?, max? } }. Fails fast on a missing required percentile
// rather than silently reporting an incomplete tail — a latency lab's whole
// point is usually the tail.
function importHistogram(source, meta) {
  if (!meta || !meta.labId || !meta.variant) {
    throw new Error("Histogram import requires meta.labId and meta.variant");
  }
  if (!source || typeof source !== "object") {
    throw new Error("Histogram import requires a source object");
  }
  const missing = REQUIRED_PERCENTILES.filter((p) => typeof source.percentiles?.[p] !== "number");
  if (missing.length > 0) {
    throw new Error(`Histogram import ("${meta.variant}"): missing required percentile(s): ${missing.join(", ")}`);
  }
  for (const field of ["count", "min", "max", "mean"]) {
    if (typeof source[field] !== "number") {
      throw new Error(`Histogram import ("${meta.variant}"): missing required field "${field}"`);
    }
  }

  return {
    schemaVersion: SCHEMA_VERSION,
    labId: meta.labId,
    variant: meta.variant,
    language: meta.language ?? null,
    harness: meta.harness ?? "histogram",
    metricKind: "histogram",
    unit: source.unit ?? null,
    statistic: {
      count: source.count,
      min: source.min,
      max: source.max,
      mean: source.mean,
      percentiles: { ...source.percentiles },
    },
    provenance: {
      rawArtifactPath: meta.rawArtifactPath ?? null,
      sourceRevision: meta.sourceRevision ?? "unversioned",
      command: meta.command ?? null,
      environmentRef: meta.environmentRef ?? null,
      importerVersion: `histogram-importer@${IMPORTER_VERSION}`,
      capturedAt: meta.capturedAt ?? null,
    },
    comparability: {
      buildMode: meta.buildMode ?? null,
      datasetId: meta.datasetId ?? null,
      semanticsFixtureHash: meta.semanticsFixtureHash ?? null,
      warmup: meta.warmup ?? null,
    },
    evidenceMaturity: meta.evidenceMaturity ?? "draft",
  };
}

export { IMPORTER_VERSION, REQUIRED_PERCENTILES, importHistogram };
