// Versioned canonical result schema (plab-003 task 2).
// Every published measurement — regardless of source harness — is normalized
// into one of these record shapes before it can be rendered. The schema is
// deliberately explicit about what's missing rather than omitting fields:
// spec.md's "Provenance chain" requirement means a reviewer must be able to
// tell, from the record alone, whether a raw artifact backs a number.

const SCHEMA_VERSION = 1;

const METRIC_KINDS = ["scalar", "histogram", "counter"];

const EVIDENCE_MATURITY = [
  // Human ran it once, informally, no raw artifact retained.
  "draft",
  // Raw artifact preserved (results/<lab-id>/<run-id>/raw/) and re-run at
  // least once with a compatible result (design.md "Review workflow").
  "reproduced",
  // Reproduced, plus profiler/perf-counter evidence attached explaining the
  // effect, not just restating the timing.
  "profiled",
  // Reproduced or profiled, reviewed, and accepted as the current
  // publication-grade number for its profile tier.
  "verified",
  // Pre-plab-003 hand-authored benchmark.md numbers with no raw harness
  // artifact ever captured. Never promoted to any of the above without a
  // real re-run — see docs/benchmark-results-migration.md.
  "legacy-unprovenanced",
];

// Requirement fields per metricKind. `null` is a valid value for an optional
// field; the key must still be present so a reader sees "not captured" vs.
// "the schema doesn't even ask for this."
const REQUIRED_TOP_LEVEL = [
  "schemaVersion",
  "labId",
  "variant",
  "language",
  "harness",
  "metricKind",
  "unit",
  "statistic",
  "provenance",
  "evidenceMaturity",
];

const REQUIRED_PROVENANCE = [
  "rawArtifactPath",
  "sourceRevision",
  "command",
  "environmentRef",
  "importerVersion",
  "capturedAt",
];

// Kind-specific statistic shapes, documented here rather than only in
// importer code, so a hand-authored legacy record (task 9) has the same
// contract to satisfy as an imported one.
//
// scalar:    { pointEstimate, error, errorKind: "ci99.9"|"ci95"|"stddev"|null,
//              statisticName: "mean"|"median", sampleCount|null }
// histogram: { count, min, max, mean, percentiles: { p50, p90, p99, p999, ... } }
// counter:   { value, event, unit }

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function missingKeys(obj, keys) {
  return keys.filter((key) => !isPlainObject(obj) || !(key in obj));
}

function validateProvenanceShape(provenance) {
  const errors = [];
  if (!isPlainObject(provenance)) {
    return ["provenance must be an object"];
  }
  const missing = missingKeys(provenance, REQUIRED_PROVENANCE);
  if (missing.length > 0) {
    errors.push(`provenance missing required field(s): ${missing.join(", ")}`);
  }
  return errors;
}

function validateScalarStatistic(statistic) {
  const errors = [];
  const required = ["pointEstimate", "errorKind", "statisticName"];
  const missing = missingKeys(statistic, required);
  if (missing.length > 0) {
    errors.push(`scalar statistic missing required field(s): ${missing.join(", ")}`);
  }
  if (isPlainObject(statistic) && typeof statistic.pointEstimate !== "number") {
    errors.push("scalar statistic.pointEstimate must be a number");
  }
  return errors;
}

function validateHistogramStatistic(statistic) {
  const errors = [];
  const required = ["count", "min", "max", "mean", "percentiles"];
  const missing = missingKeys(statistic, required);
  if (missing.length > 0) {
    errors.push(`histogram statistic missing required field(s): ${missing.join(", ")}`);
  }
  if (isPlainObject(statistic) && !isPlainObject(statistic.percentiles)) {
    errors.push("histogram statistic.percentiles must be an object");
  }
  return errors;
}

function validateCounterStatistic(statistic) {
  const errors = [];
  const required = ["value", "event"];
  const missing = missingKeys(statistic, required);
  if (missing.length > 0) {
    errors.push(`counter statistic missing required field(s): ${missing.join(", ")}`);
  }
  return errors;
}

// Validates one canonical result record. Returns { valid, errors }. Never
// throws — callers (importers, the content gate, tests) decide how to react
// to an invalid record; a schema violation is data to report, not a crash.
function validateResult(record) {
  const errors = [];

  if (!isPlainObject(record)) {
    return { valid: false, errors: ["record must be an object"] };
  }

  const missing = missingKeys(record, REQUIRED_TOP_LEVEL);
  if (missing.length > 0) {
    errors.push(`missing required top-level field(s): ${missing.join(", ")}`);
  }

  if (record.schemaVersion !== SCHEMA_VERSION) {
    errors.push(`unsupported schemaVersion "${record.schemaVersion}" (expected ${SCHEMA_VERSION})`);
  }

  if (record.metricKind && !METRIC_KINDS.includes(record.metricKind)) {
    errors.push(`unknown metricKind "${record.metricKind}" (known: ${METRIC_KINDS.join(", ")})`);
  }

  if (record.evidenceMaturity && !EVIDENCE_MATURITY.includes(record.evidenceMaturity)) {
    errors.push(`unknown evidenceMaturity "${record.evidenceMaturity}" (known: ${EVIDENCE_MATURITY.join(", ")})`);
  }

  if ("provenance" in record) {
    errors.push(...validateProvenanceShape(record.provenance));
  }

  if (record.metricKind === "scalar") {
    errors.push(...validateScalarStatistic(record.statistic));
  } else if (record.metricKind === "histogram") {
    errors.push(...validateHistogramStatistic(record.statistic));
  } else if (record.metricKind === "counter") {
    errors.push(...validateCounterStatistic(record.statistic));
  }

  // A record claiming to have been re-run/profiled/verified without a raw
  // artifact is a fabrication risk, not a schema-shape problem (spec.md "No
  // manual numbers"). "draft" is explicitly allowed a null raw artifact
  // (evidence-maturity.js: "Human ran it once, informally, no raw artifact
  // retained") — only the stages that claim reproduction require one.
  const REQUIRES_RAW_ARTIFACT = ["reproduced", "profiled", "verified"];
  if (
    REQUIRES_RAW_ARTIFACT.includes(record.evidenceMaturity) &&
    isPlainObject(record.provenance) &&
    !record.provenance.rawArtifactPath
  ) {
    errors.push(
      `evidenceMaturity "${record.evidenceMaturity}" requires provenance.rawArtifactPath — only "draft" or "legacy-unprovenanced" may have a null raw artifact`,
    );
  }

  return { valid: errors.length === 0, errors };
}

export { SCHEMA_VERSION, METRIC_KINDS, EVIDENCE_MATURITY, validateResult };
