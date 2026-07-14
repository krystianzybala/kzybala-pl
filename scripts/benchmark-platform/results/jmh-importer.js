// JMH result importer (plab-003 task 3).
// Parses JMH's own `-rf json` output format (an array of per-benchmark
// result objects, e.g. content/labs/<id>/code/java/results.json produced by
// the `-rff results.json` flag documented in
// docs/benchmark-publication-procedure.md) into the canonical schema
// (scripts/benchmark-platform/results/schema.js). Never invents a field JMH
// didn't report.
import { SCHEMA_VERSION } from "./schema.js";

const IMPORTER_VERSION = 1;

// JMH's own scoreConfidence is a [lower, upper] 99.9% CI by default
// (JMH's default confidence level unless a run overrides it) — derive a
// symmetric +/- error from it for the canonical `error` field, since that's
// how every existing benchmark.md table already reports JMH numbers
// (docs/benchmark-results-inventory.md).
function errorFromConfidenceInterval(score, confidenceInterval) {
  if (!Array.isArray(confidenceInterval) || confidenceInterval.length !== 2) {
    return null;
  }
  const [lower, upper] = confidenceInterval;
  if (typeof lower !== "number" || typeof upper !== "number") {
    return null;
  }
  return (upper - lower) / 2;
}

// Splits "pl.kzybala.lab.FalseSharingBenchmark.sharedCounters" into a short
// variant label ("sharedCounters") — JMH's `benchmark` field is always
// `<fully.qualified.Class>.<method>`; the method name is the only part that
// varies meaningfully between rows of the same lab's results.json.
function variantFromBenchmarkName(benchmarkName) {
  const parts = String(benchmarkName).split(".");
  return parts[parts.length - 1];
}

// One JMH result entry -> one canonical scalar record. `meta` supplies the
// fields JMH's JSON itself doesn't carry (labId, sourceRevision, the raw
// file's own path, the environment capture to reference) — an importer
// normalizes shape, it does not infer provenance the caller didn't supply.
// JMH's own `mode` tells us the comparison direction unambiguously: "thrpt"
// is ops/time (higher is better), every other mode ("avgt", "sample", "ss",
// "all") reports a time-like quantity (lower is better) — never guessed
// from the unit string, which can't distinguish "ops/ms" from "ms/op".
function directionFromMode(mode) {
  return mode === "thrpt" ? "higherIsBetter" : "lowerIsBetter";
}

function importOneJmhEntry(entry, meta) {
  const { labId, rawArtifactPath, sourceRevision, command, environmentRef, capturedAt } = meta;
  const primary = entry.primaryMetric ?? {};

  return {
    schemaVersion: SCHEMA_VERSION,
    labId,
    variant: variantFromBenchmarkName(entry.benchmark),
    language: "java",
    harness: "jmh",
    metricKind: "scalar",
    unit: primary.scoreUnit ?? null,
    direction: directionFromMode(entry.mode),
    statistic: {
      pointEstimate: typeof primary.score === "number" ? primary.score : null,
      error: typeof primary.scoreError === "number" ? primary.scoreError : errorFromConfidenceInterval(primary.score, primary.scoreConfidence),
      errorKind: primary.scoreConfidence ? "ci99.9" : null,
      statisticName: entry.mode === "sample" ? "median" : "mean",
      sampleCount: Array.isArray(primary.rawData) ? primary.rawData.flat().length : null,
    },
    provenance: {
      rawArtifactPath: rawArtifactPath ?? null,
      sourceRevision: sourceRevision ?? "unversioned",
      command: command ?? null,
      environmentRef: environmentRef ?? null,
      importerVersion: `jmh-importer@${IMPORTER_VERSION}`,
      capturedAt: capturedAt ?? null,
    },
    comparability: {
      buildMode: "release",
      threads: typeof entry.threads === "number" ? entry.threads : null,
      forks: typeof entry.forks === "number" ? entry.forks : null,
      warmup: entry.warmupIterations != null ? { iterations: entry.warmupIterations, time: entry.warmupTime ?? null } : null,
      datasetId: meta.datasetId ?? null,
      semanticsFixtureHash: meta.semanticsFixtureHash ?? null,
      architecture: meta.architecture ?? null,
    },
    evidenceMaturity: meta.evidenceMaturity ?? "draft",
    toolchain: {
      jmhVersion: entry.jmhVersion ?? null,
      jdkVersion: entry.jdkVersion ?? null,
      vmName: entry.vmName ?? null,
      vmVersion: entry.vmVersion ?? null,
      jvmArgs: Array.isArray(entry.jvmArgs) ? entry.jvmArgs : [],
    },
  };
}

// Parses a full JMH `-rf json` document (an array of entries) into an array
// of canonical records. Fails fast — per design.md's "Importer boundary"
// ("Invalid units, missing parameters or duplicate run ids fail fast") —
// rather than silently skipping a malformed entry.
function importJmhResults(document, meta) {
  if (!Array.isArray(document)) {
    throw new Error("JMH import expected an array (the -rf json document root)");
  }
  if (!meta || !meta.labId) {
    throw new Error("JMH import requires meta.labId");
  }
  return document.map((entry, index) => {
    if (!entry || typeof entry.benchmark !== "string") {
      throw new Error(`JMH import: entry ${index} has no "benchmark" field`);
    }
    if (!entry.primaryMetric || typeof entry.primaryMetric.scoreUnit !== "string") {
      throw new Error(`JMH import: entry ${index} ("${entry.benchmark}") has no primaryMetric.scoreUnit`);
    }
    return importOneJmhEntry(entry, meta);
  });
}

export { IMPORTER_VERSION, importJmhResults, variantFromBenchmarkName };
