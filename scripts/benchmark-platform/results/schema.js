// Versioned canonical result schema (plab-003 task 2; rewritten in the
// 2026-07-14 remediation pass after an archival-readiness audit found the
// v1 schema accepted NaN/Infinity, silently corrupted large integers,
// accepted unknown top-level fields, never validated units/modes against a
// known set, and let a bare `evidenceMaturity: "verified"` string stand in
// for evidence nothing in the record actually demonstrated). See
// docs/benchmark-results-schema.md for the full rationale.
//
// Every published measurement — regardless of source harness — is
// normalized into one of these record shapes before it can be rendered.
// Numeric measurements are never a native JS `Number` (see numeric.js);
// evidence maturity is never a caller-supplied string (see
// evidence-maturity.js) — both are validated here as *shapes*, with the
// deeper semantic checks (hash verification, filesystem containment,
// derived-maturity computation) living in provenance.js /
// evidence-maturity.js, which this module is deliberately kept free of
// importing, to avoid a circular dependency.
import { isCanonicalNumber } from "./numeric.js";
import { isKnownUnit } from "./units.js";
import { isHex64, computeCanonicalResultHash } from "./canonical-hash.js";
import { CAPABILITY_LEVELS, isCapabilityClaimAllowed } from "./capability-registry.js";

const SCHEMA_ID = "pl.kzybala.benchmark-results";
const SCHEMA_VERSION = 2;
const SUPPORTED_SCHEMA_VERSIONS = [SCHEMA_VERSION];

const METRIC_KINDS = ["scalar", "histogram", "counter"];
const LANGUAGES = ["java", "rust"];
const HARNESSES = ["jmh", "criterion", "histogram", "perf-stat"];
const DIRECTIONS = ["higherIsBetter", "lowerIsBetter"];
const BENCHMARK_MODES = ["thrpt", "avgt", "sample", "ss"];
const STATISTIC_KINDS = ["mean", "median", "slope"];
const ERROR_KINDS = ["confidence-interval", "stddev", "minmax"];

// Independent evidence dimensions (plab-003 remediation task 6) — replaces
// the single linear `evidenceMaturity` string. See evidence-maturity.js for
// how a maturity badge is *derived* (never stored) from these.
const EVIDENCE_LEGACY_LABEL = "legacy-unprovenanced";
const CORRECTNESS_STATES = ["passed", "failed", "not-run"];
const ENVIRONMENT_STATES = ["native-controlled", "native-uncontrolled", "container", "vm", "emulated", "unknown"];
const PROFILING_STATES = ["present", "absent"];
const COMPARABILITY_STATES = ["validated", "non-comparable", "invalid", "not-applicable"];

const REQUIRED_TOP_LEVEL = [
  "schemaId",
  "schemaVersion",
  "labId",
  "variant",
  "language",
  "harness",
  "metricKind",
  "unit",
  "direction",
  "mode",
  "parameters",
  "statistic",
  "provenance",
  "evidence",
];
const OPTIONAL_TOP_LEVEL = ["toolchain", "comparability", "notes"];
const ALL_TOP_LEVEL = new Set([...REQUIRED_TOP_LEVEL, ...OPTIONAL_TOP_LEVEL]);

const HASH_REF_FIELDS = ["dataset", "semanticFixture", "environmentManifest", "toolchainManifest", "benchmarkProfile", "correctnessGate", "profilingArtifact", "renderedArtifact"];
const REQUIRED_PROVENANCE = ["rawArtifact", "canonicalResultHash", "sourceCommit", ...HASH_REF_FIELDS, "command", "capturedAt", "importerVersion"];

const REQUIRED_EVIDENCE = ["legacy", "correctness", "environment", "reproduction", "profiling", "comparability", "reviewer", "importerCapability", "warnings"];

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function missingKeys(obj, keys) {
  return keys.filter((key) => !isPlainObject(obj) || !(key in obj));
}

function isNonEmptyString(value) {
  return typeof value === "string" && value.length > 0;
}

function isNullableCanonicalNumber(value) {
  return value === null || isCanonicalNumber(value);
}

function isNullableHex64(value) {
  return value === null || isHex64(value);
}

function validateHashRef(ref, fieldName) {
  const errors = [];
  if (!isPlainObject(ref)) {
    return [`provenance.${fieldName} must be an object ({ ref, hash })`];
  }
  const missing = missingKeys(ref, ["ref", "hash"]);
  if (missing.length > 0) errors.push(`provenance.${fieldName} missing field(s): ${missing.join(", ")}`);
  if ("ref" in ref && ref.ref !== null && typeof ref.ref !== "string") errors.push(`provenance.${fieldName}.ref must be a string or null`);
  if ("hash" in ref && !isNullableHex64(ref.hash)) errors.push(`provenance.${fieldName}.hash must be a lowercase sha256 hex string or null`);
  return errors;
}

function validateEstimate(estimate, path) {
  if (!isPlainObject(estimate)) return [`${path} must be an object`];
  const errors = [];
  const missing = missingKeys(estimate, ["pointEstimate", "error", "errorKind", "confidenceLevel"]);
  if (missing.length > 0) errors.push(`${path} missing field(s): ${missing.join(", ")}`);
  if ("pointEstimate" in estimate && !isCanonicalNumber(estimate.pointEstimate)) errors.push(`${path}.pointEstimate must be a canonical number { value, numericType }`);
  if ("error" in estimate && !isNullableCanonicalNumber(estimate.error)) errors.push(`${path}.error must be a canonical number or null`);
  if ("errorKind" in estimate && estimate.errorKind !== null && !ERROR_KINDS.includes(estimate.errorKind)) errors.push(`${path}.errorKind "${estimate.errorKind}" is not one of: ${ERROR_KINDS.join(", ")}`);
  if ("confidenceLevel" in estimate && !isNullableCanonicalNumber(estimate.confidenceLevel)) errors.push(`${path}.confidenceLevel must be a canonical number or null`);
  return errors;
}

function validateScalarStatistic(statistic) {
  if (!isPlainObject(statistic)) return ["scalar statistic must be an object"];
  const errors = [];
  const required = ["primary", "estimates", "sampleCount", "percentiles", "rawSamples", "outliers", "secondaryMetrics", "derivedFrom"];
  const missing = missingKeys(statistic, required);
  if (missing.length > 0) errors.push(`scalar statistic missing field(s): ${missing.join(", ")}`);
  if (missing.length > 0) return errors;

  if (!STATISTIC_KINDS.includes(statistic.primary)) {
    errors.push(`scalar statistic.primary "${statistic.primary}" is not one of: ${STATISTIC_KINDS.join(", ")}`);
  }
  if (!isPlainObject(statistic.estimates)) {
    errors.push("scalar statistic.estimates must be an object { mean, median, slope }");
  } else {
    const estimateMissing = missingKeys(statistic.estimates, STATISTIC_KINDS);
    if (estimateMissing.length > 0) errors.push(`scalar statistic.estimates missing key(s): ${estimateMissing.join(", ")}`);
    let anyPresent = false;
    for (const kind of STATISTIC_KINDS) {
      const value = statistic.estimates[kind];
      if (value === null) continue;
      anyPresent = true;
      errors.push(...validateEstimate(value, `scalar statistic.estimates.${kind}`));
    }
    if (!anyPresent) errors.push("scalar statistic.estimates has no usable estimate (mean, median and slope are all null)");
    if (STATISTIC_KINDS.includes(statistic.primary) && statistic.estimates[statistic.primary] === null) {
      errors.push(`scalar statistic.primary is "${statistic.primary}" but statistic.estimates.${statistic.primary} is null`);
    }
  }
  if (statistic.sampleCount !== null && !(isCanonicalNumber(statistic.sampleCount) && statistic.sampleCount.numericType === "integer")) {
    errors.push("scalar statistic.sampleCount must be an integer canonical number or null");
  }
  if (statistic.percentiles !== null) {
    if (!isPlainObject(statistic.percentiles)) {
      errors.push("scalar statistic.percentiles must be an object or null");
    } else {
      for (const [key, value] of Object.entries(statistic.percentiles)) {
        if (!isCanonicalNumber(value)) errors.push(`scalar statistic.percentiles.${key} must be a canonical number`);
      }
    }
  }
  if (statistic.rawSamples !== null) {
    if (!Array.isArray(statistic.rawSamples)) {
      errors.push("scalar statistic.rawSamples must be an array or null");
    } else if (statistic.rawSamples.some((value) => !isCanonicalNumber(value))) {
      errors.push("scalar statistic.rawSamples must contain only canonical numbers");
    }
  }
  if (statistic.secondaryMetrics !== null) {
    if (!isPlainObject(statistic.secondaryMetrics)) {
      errors.push("scalar statistic.secondaryMetrics must be an object or null");
    } else {
      for (const [name, metric] of Object.entries(statistic.secondaryMetrics)) {
        if (!isPlainObject(metric) || !isCanonicalNumber(metric.pointEstimate) || !isNullableCanonicalNumber(metric.error) || (metric.unit !== null && typeof metric.unit !== "string")) {
          errors.push(`scalar statistic.secondaryMetrics.${name} must be { pointEstimate: canonical number, error: canonical number|null, unit: string|null }`);
        }
      }
    }
  }
  if (statistic.derivedFrom !== null) {
    if (!isPlainObject(statistic.derivedFrom) || !isNonEmptyString(statistic.derivedFrom.metric) || !isHex64(statistic.derivedFrom.sourceRecordHash)) {
      errors.push("scalar statistic.derivedFrom must be null or { metric: string, sourceRecordHash: hex64 }");
    }
  }
  return errors;
}

function validateHistogramStatistic(statistic) {
  if (!isPlainObject(statistic)) return ["histogram statistic must be an object"];
  const errors = [];
  const required = ["format", "count", "min", "max", "mean", "percentiles", "significantDigits", "overflow", "coordinatedOmission"];
  const missing = missingKeys(statistic, required);
  if (missing.length > 0) errors.push(`histogram statistic missing field(s): ${missing.join(", ")}`);
  if (missing.length > 0) return errors;

  if (!isNonEmptyString(statistic.format)) errors.push("histogram statistic.format must be a non-empty string (e.g. \"hdrhistogram\", \"generic-percentile-map\")");
  for (const field of ["count", "min", "max", "mean"]) {
    if (!isCanonicalNumber(statistic[field])) errors.push(`histogram statistic.${field} must be a canonical number`);
  }
  const requiredPercentiles = ["p50", "p90", "p99"];
  if (!isPlainObject(statistic.percentiles)) {
    errors.push("histogram statistic.percentiles must be an object");
  } else {
    const missingPercentiles = requiredPercentiles.filter((p) => !isCanonicalNumber(statistic.percentiles[p]));
    if (missingPercentiles.length > 0) errors.push(`histogram statistic.percentiles missing required percentile(s): ${missingPercentiles.join(", ")}`);
  }
  if (statistic.significantDigits !== null && !(Number.isInteger(statistic.significantDigits) && statistic.significantDigits >= 0)) {
    errors.push("histogram statistic.significantDigits must be a non-negative integer or null");
  }
  if (statistic.overflow !== null && (!isPlainObject(statistic.overflow) || typeof statistic.overflow.saturated !== "boolean")) {
    errors.push("histogram statistic.overflow must be null or { saturated: boolean, saturatedCount }");
  }
  if (statistic.coordinatedOmission !== null && (!isPlainObject(statistic.coordinatedOmission) || typeof statistic.coordinatedOmission.corrected !== "boolean")) {
    errors.push("histogram statistic.coordinatedOmission must be null or { corrected: boolean, method }");
  }
  return errors;
}

function validateCounterStatistic(statistic) {
  if (!isPlainObject(statistic)) return ["counter statistic must be an object"];
  const errors = [];
  const required = ["event", "value", "notCounted", "notSupported", "rawCountText", "timeRunningNs", "percentRunning", "runsAggregated", "stddevPercent", "derivedMetric"];
  const missing = missingKeys(statistic, required);
  if (missing.length > 0) errors.push(`counter statistic missing field(s): ${missing.join(", ")}`);
  if (missing.length > 0) return errors;

  if (!isNonEmptyString(statistic.event)) errors.push("counter statistic.event must be a non-empty string");
  if (typeof statistic.notCounted !== "boolean") errors.push("counter statistic.notCounted must be a boolean");
  if (typeof statistic.notSupported !== "boolean") errors.push("counter statistic.notSupported must be a boolean");
  if (statistic.notCounted && statistic.notSupported) errors.push("counter statistic cannot be both notCounted and notSupported (contradictory)");
  if (statistic.notCounted || statistic.notSupported) {
    if (statistic.value !== null) errors.push("counter statistic.value must be null when notCounted/notSupported is true");
  } else if (!isCanonicalNumber(statistic.value)) {
    errors.push("counter statistic.value must be a canonical number when the event was counted");
  }
  if (!isNullableCanonicalNumber(statistic.timeRunningNs)) errors.push("counter statistic.timeRunningNs must be a canonical number or null");
  if (!isNullableCanonicalNumber(statistic.percentRunning)) errors.push("counter statistic.percentRunning must be a canonical number or null");
  if (statistic.runsAggregated !== null && !(Number.isInteger(statistic.runsAggregated) && statistic.runsAggregated >= 1)) {
    errors.push("counter statistic.runsAggregated must be a positive integer or null");
  }
  if (!isNullableCanonicalNumber(statistic.stddevPercent)) errors.push("counter statistic.stddevPercent must be a canonical number or null");
  if (statistic.derivedMetric !== null) {
    if (!isPlainObject(statistic.derivedMetric) || !isCanonicalNumber(statistic.derivedMetric.value) || !isNonEmptyString(statistic.derivedMetric.unit)) {
      errors.push("counter statistic.derivedMetric must be null or { value: canonical number, unit: string }");
    }
  }
  return errors;
}

function validateProvenanceShape(provenance) {
  if (!isPlainObject(provenance)) return ["provenance must be an object"];
  const errors = [];
  const missing = missingKeys(provenance, REQUIRED_PROVENANCE);
  if (missing.length > 0) errors.push(`provenance missing required field(s): ${missing.join(", ")}`);
  if (missing.length > 0) return errors;

  if (provenance.rawArtifact !== null) {
    if (!isPlainObject(provenance.rawArtifact) || !isNonEmptyString(provenance.rawArtifact.path) || !isHex64(provenance.rawArtifact.sha256)) {
      errors.push("provenance.rawArtifact must be null or { path: string, sha256: hex64 }");
    }
  }
  if (!isHex64(provenance.canonicalResultHash)) errors.push("provenance.canonicalResultHash must be a lowercase sha256 hex string");

  if (!isPlainObject(provenance.sourceCommit)) {
    errors.push("provenance.sourceCommit must be an object { revision, dirty, diffHash }");
  } else {
    if (!isNonEmptyString(provenance.sourceCommit.revision)) errors.push("provenance.sourceCommit.revision must be a non-empty string");
    if (provenance.sourceCommit.dirty !== null && typeof provenance.sourceCommit.dirty !== "boolean") errors.push("provenance.sourceCommit.dirty must be a boolean or null");
    if (!isNullableHex64(provenance.sourceCommit.diffHash)) errors.push("provenance.sourceCommit.diffHash must be a sha256 hex string or null");
    if (provenance.sourceCommit.dirty === true && provenance.sourceCommit.diffHash === null) {
      errors.push("provenance.sourceCommit.dirty is true but diffHash is null (a dirty tree must hash the diff, not merely flag it)");
    }
  }

  for (const field of HASH_REF_FIELDS) {
    errors.push(...validateHashRef(provenance[field], field));
  }

  if (provenance.command !== null && typeof provenance.command !== "string") errors.push("provenance.command must be a string or null");
  if (provenance.capturedAt !== null && typeof provenance.capturedAt !== "string") errors.push("provenance.capturedAt must be an ISO date string or null");
  if (!isNonEmptyString(provenance.importerVersion) || !/^[a-zA-Z0-9_-]+@\d+$/.test(provenance.importerVersion)) {
    errors.push('provenance.importerVersion must match "<importer-name>@<version>"');
  }

  return errors;
}

function validateEvidenceShape(evidence, provenance) {
  if (!isPlainObject(evidence)) return ["evidence must be an object"];
  const errors = [];
  const missing = missingKeys(evidence, REQUIRED_EVIDENCE);
  if (missing.length > 0) errors.push(`evidence missing required field(s): ${missing.join(", ")}`);
  if (missing.length > 0) return errors;

  if (typeof evidence.legacy !== "boolean") errors.push("evidence.legacy must be a boolean");
  if (!CORRECTNESS_STATES.includes(evidence.correctness)) errors.push(`evidence.correctness "${evidence.correctness}" is not one of: ${CORRECTNESS_STATES.join(", ")}`);
  if (!ENVIRONMENT_STATES.includes(evidence.environment)) errors.push(`evidence.environment "${evidence.environment}" is not one of: ${ENVIRONMENT_STATES.join(", ")}`);
  if (!isPlainObject(evidence.reproduction) || !Number.isInteger(evidence.reproduction.required) || evidence.reproduction.required < 0 || !Number.isInteger(evidence.reproduction.completed) || evidence.reproduction.completed < 0) {
    errors.push("evidence.reproduction must be { required: non-negative int, completed: non-negative int }");
  }
  if (!PROFILING_STATES.includes(evidence.profiling)) errors.push(`evidence.profiling "${evidence.profiling}" is not one of: ${PROFILING_STATES.join(", ")}`);
  if (!COMPARABILITY_STATES.includes(evidence.comparability)) errors.push(`evidence.comparability "${evidence.comparability}" is not one of: ${COMPARABILITY_STATES.join(", ")}`);
  if (evidence.reviewer !== null && (!isPlainObject(evidence.reviewer) || !isNonEmptyString(evidence.reviewer.approvedBy) || !isNonEmptyString(evidence.reviewer.approvedAt))) {
    errors.push("evidence.reviewer must be null or { approvedBy: non-empty string, approvedAt: ISO date string }");
  }
  if (!CAPABILITY_LEVELS.includes(evidence.importerCapability)) errors.push(`evidence.importerCapability "${evidence.importerCapability}" is not one of: ${CAPABILITY_LEVELS.join(", ")}`);
  if (!Array.isArray(evidence.warnings) || evidence.warnings.some((w) => typeof w !== "string")) errors.push("evidence.warnings must be an array of strings");

  if (errors.length > 0) return errors;

  // A source record's capability claim can never exceed the ceiling its
  // *importer* is registered for (capability-registry.js) — this is the
  // check that stops a hand-edited record from claiming
  // "live-publication-validated" while its importer is still "fixture-only".
  if (isPlainObject(provenance) && isNonEmptyString(provenance.importerVersion) && !isCapabilityClaimAllowed(provenance.importerVersion, evidence.importerCapability)) {
    errors.push(`evidence.importerCapability "${evidence.importerCapability}" exceeds the registered capability ceiling for importer "${provenance.importerVersion}" (see capability-registry.js)`);
  }

  // legacy-unprovenanced lockdown (audit: "cannot be upgraded by adding
  // fabricated references"): a legacy record's other evidence dimensions
  // and hash references must all sit at their untouched baseline. This is
  // defense in depth — evidence-maturity.js's derivation already treats
  // `legacy: true` as an unconditional terminal state regardless of what
  // else is set — but rejecting the fabricated shape outright at schema
  // level means a reviewer sees an explicit error instead of a record that
  // merely "doesn't help."
  if (evidence.legacy === true) {
    const violations = [];
    if (evidence.correctness !== "not-run") violations.push("evidence.correctness");
    if (evidence.environment !== "unknown") violations.push("evidence.environment");
    if (!(evidence.reproduction.required === 0 && evidence.reproduction.completed === 0)) violations.push("evidence.reproduction");
    if (evidence.profiling !== "absent") violations.push("evidence.profiling");
    if (evidence.comparability !== "not-applicable") violations.push("evidence.comparability");
    if (evidence.reviewer !== null) violations.push("evidence.reviewer");
    if (evidence.importerCapability !== "fixture-only") violations.push("evidence.importerCapability");
    if (isPlainObject(provenance)) {
      if (provenance.rawArtifact !== null) violations.push("provenance.rawArtifact");
      for (const field of HASH_REF_FIELDS) {
        if (provenance[field]?.ref !== null || provenance[field]?.hash !== null) violations.push(`provenance.${field}`);
      }
    }
    if (violations.length > 0) {
      errors.push(`evidence.legacy is true but non-baseline value(s) were set (legacy-unprovenanced is terminal and cannot carry fabricated advancement evidence): ${violations.join(", ")}`);
    }
  }

  return errors;
}

// Validates one canonical result record. Returns { valid, errors }. Never
// throws — callers (importers, the content gate, tests) decide how to react
// to an invalid record.
function validateResult(record) {
  try {
    if (!isPlainObject(record)) {
      return { valid: false, errors: ["record must be an object"] };
    }

    const unknownKeys = Object.keys(record).filter((key) => !ALL_TOP_LEVEL.has(key));
    const errors = unknownKeys.length > 0 ? [`unknown top-level field(s) not in the schema allow-list: ${unknownKeys.join(", ")}`] : [];

    const missing = missingKeys(record, REQUIRED_TOP_LEVEL);
    if (missing.length > 0) {
      errors.push(`missing required top-level field(s): ${missing.join(", ")}`);
      return { valid: false, errors };
    }

    if (record.schemaId !== SCHEMA_ID) errors.push(`unsupported schemaId "${record.schemaId}" (expected "${SCHEMA_ID}")`);
    if (!SUPPORTED_SCHEMA_VERSIONS.includes(record.schemaVersion)) {
      errors.push(`unsupported schemaVersion "${record.schemaVersion}" (supported: ${SUPPORTED_SCHEMA_VERSIONS.join(", ")} — use migrateRecord() for older versions)`);
      return { valid: false, errors };
    }

    if (!METRIC_KINDS.includes(record.metricKind)) errors.push(`unknown metricKind "${record.metricKind}" (known: ${METRIC_KINDS.join(", ")})`);
    if (record.language !== null && !LANGUAGES.includes(record.language)) errors.push(`unknown language "${record.language}" (known: ${LANGUAGES.join(", ")}, or null)`);
    if (!HARNESSES.includes(record.harness)) errors.push(`unknown harness "${record.harness}" (known: ${HARNESSES.join(", ")})`);
    if (!isKnownUnit(record.unit)) errors.push(`unknown unit "${record.unit}" — not present in the unit registry (units.js)`);
    if (!isNonEmptyString(record.labId)) errors.push("labId must be a non-empty string");
    if (!isNonEmptyString(record.variant)) errors.push("variant must be a non-empty string");

    // Cross-field: harness/language pairing (audit: "contradictory metadata").
    if (record.harness === "jmh" && record.language !== "java") errors.push('harness "jmh" requires language "java"');
    if (record.harness === "criterion" && record.language !== "rust") errors.push('harness "criterion" requires language "rust"');

    // Cross-field: mode is JMH-specific and required exactly there.
    if (record.harness === "jmh") {
      if (!BENCHMARK_MODES.includes(record.mode)) errors.push(`harness "jmh" requires an explicit mode in ${JSON.stringify(BENCHMARK_MODES)} (got "${record.mode}")`);
    } else if (record.mode !== null) {
      errors.push(`mode must be null for harness "${record.harness}" (mode is JMH-specific)`);
    }

    // Cross-field: direction is meaningful only for a single scalar value.
    if (record.metricKind === "scalar") {
      if (!DIRECTIONS.includes(record.direction)) errors.push(`metricKind "scalar" requires direction in ${JSON.stringify(DIRECTIONS)} (got "${record.direction}")`);
    } else if (record.direction !== null) {
      errors.push(`direction must be null for metricKind "${record.metricKind}"`);
    }

    if (!isPlainObject(record.parameters) || Object.values(record.parameters).some((v) => typeof v !== "string")) {
      errors.push("parameters must be an object mapping string keys to string values");
    }

    if (record.metricKind === "scalar") {
      errors.push(...validateScalarStatistic(record.statistic));
    } else if (record.metricKind === "histogram") {
      errors.push(...validateHistogramStatistic(record.statistic));
    } else if (record.metricKind === "counter") {
      errors.push(...validateCounterStatistic(record.statistic));
    }

    errors.push(...validateProvenanceShape(record.provenance));

    // Tamper/hash-mismatch detection: the canonical result hash is
    // recomputed from the statistic actually present and compared, not
    // trusted as stored (audit finding C1).
    if (isPlainObject(record.provenance) && isHex64(record.provenance.canonicalResultHash) && isPlainObject(record.statistic)) {
      const recomputed = computeCanonicalResultHash(record.statistic);
      if (recomputed !== record.provenance.canonicalResultHash) {
        errors.push(`provenance.canonicalResultHash does not match the record's own statistic (stored "${record.provenance.canonicalResultHash}", recomputed "${recomputed}") — the statistic was modified after import without recomputing the hash`);
      }
    }

    errors.push(...validateEvidenceShape(record.evidence, record.provenance));

    if (record.notes !== undefined && (!Array.isArray(record.notes) || record.notes.some((n) => typeof n !== "string"))) {
      errors.push("notes must be an array of strings when present");
    }

    return { valid: errors.length === 0, errors };
  } catch (err) {
    return { valid: false, errors: [`schema validation threw: ${err.message}`] };
  }
}

// Migration entry point for a v1 (pre-remediation) record: maps the old
// linear `evidenceMaturity` string and bare-Number `statistic` shape onto
// the v2 shape conservatively. A migrated record is NEVER granted more
// trust than "fixture-only"/draft-equivalent evidence regardless of its old
// maturity label — the old pipeline's "verified" was demonstrably not
// enforced (see the audit), so it carries no weight here. This exists so a
// v1 record can be inspected/re-validated, not to quietly relabel it as
// trustworthy.
function migrateRecord(record) {
  if (!isPlainObject(record) || record.schemaVersion !== 1) {
    throw new Error("migrateRecord only supports schemaVersion 1 input");
  }
  throw new Error(
    "schemaVersion 1 -> 2 migration requires re-deriving canonical numeric values from original source text, which this repository's v1 data never retained (v1 stored native Number only) — there is no lossless migration path for v1 statistic values. Re-import from the original raw artifact instead.",
  );
}

// A stable identity for cross-manifest duplicate detection: same lab,
// variant, language, harness, mode and @Param values is the same logical
// measurement wherever it was imported from. Used by the content gate to
// catch duplicates across *combined* manifests (a single JMH document's
// internal duplicates are already caught by jmh-importer.js at import
// time).
function resultIdentity(record) {
  const params = isPlainObject(record.parameters) ? record.parameters : {};
  const sortedParams = Object.keys(params)
    .sort()
    .map((key) => `${key}=${params[key]}`)
    .join("&");
  return `${record.labId} ${record.variant} ${record.language} ${record.harness} ${record.mode} ${sortedParams}`;
}

function findDuplicateIdentities(records) {
  const byIdentity = new Map();
  records.forEach((record, index) => {
    const identity = resultIdentity(record);
    if (!byIdentity.has(identity)) byIdentity.set(identity, []);
    byIdentity.get(identity).push(index);
  });
  return [...byIdentity.entries()].filter(([, indices]) => indices.length > 1).map(([identity, indices]) => ({ identity, indices }));
}

export {
  SCHEMA_ID,
  SCHEMA_VERSION,
  SUPPORTED_SCHEMA_VERSIONS,
  METRIC_KINDS,
  LANGUAGES,
  HARNESSES,
  DIRECTIONS,
  BENCHMARK_MODES,
  STATISTIC_KINDS,
  ERROR_KINDS,
  EVIDENCE_LEGACY_LABEL,
  CORRECTNESS_STATES,
  ENVIRONMENT_STATES,
  PROFILING_STATES,
  COMPARABILITY_STATES,
  HASH_REF_FIELDS,
  isPlainObject,
  validateResult,
  migrateRecord,
  resultIdentity,
  findDuplicateIdentities,
};
