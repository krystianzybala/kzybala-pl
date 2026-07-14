#!/usr/bin/env node
// Unit and golden-file tests for the results/provenance/publication pipeline
// (plab-003): schema validation, JMH/Criterion/histogram/perf-counter
// importers, provenance-chain validation, deterministic rendering,
// evidence-maturity transitions, regression comparison, and the legacy-data
// migration's fidelity against the benchmark.md prose it was transcribed
// from. No JVM/Cargo/perf required — these exercise pure JS logic against
// fixtures (scripts/benchmark-platform/results/__fixtures__/).
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { validateResult, SCHEMA_VERSION } from "./benchmark-platform/results/schema.js";
import { importJmhResults, variantFromBenchmarkName } from "./benchmark-platform/results/jmh-importer.js";
import { importCriterionEstimates } from "./benchmark-platform/results/criterion-importer.js";
import { importHistogram } from "./benchmark-platform/results/histogram-importer.js";
import { importPerfCounterCsv } from "./benchmark-platform/results/perf-counter-importer.js";
import { validateProvenanceChain, validateRecordPairComparability } from "./benchmark-platform/results/provenance.js";
import { renderTable, renderChartSeries, renderHash } from "./benchmark-platform/results/render.js";
import { canTransition, transition, badgeFor } from "./benchmark-platform/results/evidence-maturity.js";
import { compareToHistory, findBaseline } from "./benchmark-platform/results/regression.js";
import { LEGACY_LAB_IDS, legacyResultsFor } from "./benchmark-platform/results/legacy-data.js";

const FIXTURES = join(import.meta.dirname, "benchmark-platform", "results", "__fixtures__");
const LABS_ROOT = join(import.meta.dirname, "..", "content", "labs");

const FIXED_META = {
  labId: "false-sharing",
  sourceRevision: "abc1234",
  rawArtifactPath: null,
  environmentRef: "env-fixture-1",
  capturedAt: "2026-01-01T00:00:00.000Z",
  command: "java -jar target/benchmarks.jar",
};

// --- schema ---

test("schema: a well-formed scalar record validates", () => {
  const [record] = importJmhResults(JSON.parse(readFileSync(join(FIXTURES, "jmh-sample.json"), "utf8")), FIXED_META);
  const { valid, errors } = validateResult(record);
  assert.equal(valid, true, errors.join("; "));
});

test("schema: missing required top-level field is reported", () => {
  const { valid, errors } = validateResult({ schemaVersion: SCHEMA_VERSION });
  assert.equal(valid, false);
  assert.ok(errors.some((e) => e.includes("missing required top-level field")));
});

test("schema: unknown evidenceMaturity is reported", () => {
  const [record] = importJmhResults(JSON.parse(readFileSync(join(FIXTURES, "jmh-sample.json"), "utf8")), FIXED_META);
  record.evidenceMaturity = "nonsense";
  const { valid, errors } = validateResult(record);
  assert.equal(valid, false);
  assert.ok(errors.some((e) => e.includes("unknown evidenceMaturity")));
});

test("schema: non-legacy record without a raw artifact is rejected", () => {
  const [record] = importJmhResults(JSON.parse(readFileSync(join(FIXTURES, "jmh-sample.json"), "utf8")), { ...FIXED_META, evidenceMaturity: "verified" });
  const { valid, errors } = validateResult(record);
  assert.equal(valid, false);
  assert.ok(errors.some((e) => e.includes("requires provenance.rawArtifactPath")));
});

// --- JMH importer (golden file) ---

test("jmh-importer: golden — imports the fixture into the expected canonical shape", () => {
  const document = JSON.parse(readFileSync(join(FIXTURES, "jmh-sample.json"), "utf8"));
  const records = importJmhResults(document, FIXED_META);
  const golden = JSON.parse(readFileSync(join(FIXTURES, "jmh-sample.golden.json"), "utf8"));
  assert.deepEqual(records, golden);
});

test("jmh-importer: variantFromBenchmarkName strips the fully-qualified class prefix", () => {
  assert.equal(variantFromBenchmarkName("pl.kzybala.lab.FalseSharingBenchmark.sharedCounters"), "sharedCounters");
});

test("jmh-importer: throws on a non-array document", () => {
  assert.throws(() => importJmhResults({ not: "an array" }, FIXED_META), /expected an array/);
});

test("jmh-importer: throws fast on an entry missing primaryMetric.scoreUnit", () => {
  assert.throws(() => importJmhResults([{ benchmark: "x.Y.z", primaryMetric: {} }], FIXED_META), /no primaryMetric.scoreUnit/);
});

test("jmh-importer: thrpt mode is higherIsBetter, other modes are lowerIsBetter", () => {
  const document = JSON.parse(readFileSync(join(FIXTURES, "jmh-sample.json"), "utf8"));
  const [record] = importJmhResults(document, FIXED_META);
  assert.equal(record.direction, "higherIsBetter");
  const avgtDoc = [{ ...document[0], mode: "avgt" }];
  const [avgtRecord] = importJmhResults(avgtDoc, FIXED_META);
  assert.equal(avgtRecord.direction, "lowerIsBetter");
});

// --- Criterion importer (golden file) ---

test("criterion-importer: golden — imports the fixture into the expected canonical shape", () => {
  const estimates = JSON.parse(readFileSync(join(FIXTURES, "criterion-estimates-sample.json"), "utf8"));
  const record = importCriterionEstimates(estimates, { ...FIXED_META, labId: "false-sharing", variant: "shared", language: "rust", harness: "criterion" });
  const golden = JSON.parse(readFileSync(join(FIXTURES, "criterion-estimates.golden.json"), "utf8"));
  assert.deepEqual(record, golden);
});

test("criterion-importer: prefers median over mean when both are present", () => {
  const estimates = JSON.parse(readFileSync(join(FIXTURES, "criterion-estimates-sample.json"), "utf8"));
  const record = importCriterionEstimates(estimates, { ...FIXED_META, variant: "shared" });
  assert.equal(record.statistic.statisticName, "median");
});

test("criterion-importer: throws when neither median nor mean is usable", () => {
  assert.throws(() => importCriterionEstimates({}, { ...FIXED_META, variant: "shared" }), /neither a usable/);
});

// --- histogram importer ---

test("histogram-importer: imports a valid percentile map", () => {
  const record = importHistogram(
    { count: 1000, min: 1, max: 500, mean: 42.5, unit: "µs", percentiles: { p50: 40, p90: 80, p99: 200, p999: 400 } },
    { labId: "coordinated-omission-load-generation", variant: "response-latency", language: "java", harness: "hdrhistogram" },
  );
  assert.equal(validateResult(record).valid, true);
  assert.equal(record.statistic.percentiles.p99, 200);
});

test("histogram-importer: fails fast on a missing required percentile", () => {
  assert.throws(
    () => importHistogram({ count: 1, min: 1, max: 1, mean: 1, percentiles: { p50: 1 } }, { labId: "x", variant: "y" }),
    /missing required percentile/,
  );
});

// --- perf-counter importer ---

test("perf-counter-importer: parses the documented `perf stat -x,` CSV shape", () => {
  const csv = readFileSync(join(FIXTURES, "perf-stat-sample.csv"), "utf8");
  const records = importPerfCounterCsv(csv, { labId: "cache-hierarchy", variant: "randomLarge" });
  assert.equal(records.length, 4);
  assert.equal(records[0].statistic.event, "cache-misses");
  assert.equal(records[0].statistic.value, 12345678);
});

test("perf-counter-importer: <not counted> lines report value null, notCounted true", () => {
  const csv = readFileSync(join(FIXTURES, "perf-stat-sample.csv"), "utf8");
  const records = importPerfCounterCsv(csv, { labId: "cache-hierarchy", variant: "randomLarge" });
  const branchMisses = records.find((r) => r.statistic.event === "branch-misses");
  assert.equal(branchMisses.statistic.value, null);
  assert.equal(branchMisses.statistic.notCounted, true);
});

test("perf-counter-importer: throws on input with no parseable event line", () => {
  assert.throws(() => importPerfCounterCsv("# just a comment\n", { labId: "x", variant: "y" }), /no parseable event line/);
});

// --- provenance validation ---

test("provenance: legacy-unprovenanced record with null rawArtifactPath is valid", () => {
  const [record] = legacyResultsFor("false-sharing");
  const { valid, errors } = validateProvenanceChain(record);
  assert.equal(valid, true, errors.join("; "));
});

test("provenance: a draft record with a null rawArtifactPath is valid (draft never claims reproduction)", () => {
  const document = JSON.parse(readFileSync(join(FIXTURES, "jmh-sample.json"), "utf8"));
  const [record] = importJmhResults(document, { ...FIXED_META, evidenceMaturity: "draft", environmentRef: "env-1" });
  const { valid, errors } = validateProvenanceChain(record);
  assert.equal(valid, true, errors.join("; "));
});

test("provenance: a 'verified' record with a null rawArtifactPath is invalid", () => {
  const document = JSON.parse(readFileSync(join(FIXTURES, "jmh-sample.json"), "utf8"));
  const [record] = importJmhResults(document, { ...FIXED_META, evidenceMaturity: "verified", environmentRef: "env-1" });
  const { valid, errors } = validateProvenanceChain(record);
  assert.equal(valid, false);
  assert.ok(errors.some((e) => e.includes("rawArtifactPath")));
});

test("provenance: a nonsense sourceRevision is rejected", () => {
  const document = JSON.parse(readFileSync(join(FIXTURES, "jmh-sample.json"), "utf8"));
  const [record] = importJmhResults(document, { ...FIXED_META, sourceRevision: "not-a-hash!" });
  const { errors } = validateProvenanceChain(record);
  assert.ok(errors.some((e) => e.includes("sourceRevision")));
});

test("provenance: validateRecordPairComparability flags a debug/release mismatch", () => {
  const document = JSON.parse(readFileSync(join(FIXTURES, "jmh-sample.json"), "utf8"));
  const [javaRecord] = importJmhResults(document, FIXED_META);
  const rustRecord = { comparability: { buildMode: "debug", datasetId: "d1", semanticsFixtureHash: "h1", warmup: { iterations: 1, time: "1s" } } };
  const result = validateRecordPairComparability(javaRecord, rustRecord);
  assert.equal(result.status, "invalid");
});

// --- deterministic rendering ---

test("render: renderTable is stable regardless of input order", () => {
  const document = JSON.parse(readFileSync(join(FIXTURES, "jmh-sample.json"), "utf8"));
  const records = importJmhResults(document, FIXED_META);
  const forward = renderTable(records);
  const reversed = renderTable([...records].reverse());
  assert.equal(renderHash(forward), renderHash(reversed));
});

test("render: renderTable never rounds the raw pointEstimate/error, only `display`", () => {
  const document = JSON.parse(readFileSync(join(FIXTURES, "jmh-sample.json"), "utf8"));
  const records = importJmhResults(document, FIXED_META);
  const table = renderTable(records);
  const source = records.find((r) => r.variant === "sharedCounters");
  const row = table.find((r) => r.variant === "sharedCounters");
  assert.equal(row.pointEstimate, source.statistic.pointEstimate);
  assert.notEqual(row.display.pointEstimate, undefined);
});

test("render: renderChartSeries groups by (labId, language, harness)", () => {
  const document = JSON.parse(readFileSync(join(FIXTURES, "jmh-sample.json"), "utf8"));
  const records = importJmhResults(document, FIXED_META);
  const series = renderChartSeries(records);
  assert.equal(series.length, 1);
  assert.equal(series[0].points.length, 2);
});

// --- evidence maturity ---

test("evidence-maturity: draft -> reproduced -> verified is a legal path", () => {
  assert.equal(canTransition("draft", "reproduced"), true);
  assert.equal(canTransition("reproduced", "verified"), true);
});

test("evidence-maturity: verified cannot skip straight back to profiled", () => {
  assert.equal(canTransition("verified", "profiled"), false);
});

test("evidence-maturity: verified may regress to draft (invalidated provenance)", () => {
  assert.equal(canTransition("verified", "draft"), true);
});

test("evidence-maturity: legacy-unprovenanced has no legal transitions", () => {
  assert.equal(canTransition("legacy-unprovenanced", "draft"), false);
});

test("evidence-maturity: transition() throws on an illegal move", () => {
  assert.throws(() => transition("draft", "verified"), /illegal evidence-maturity transition/);
});

test("evidence-maturity: badgeFor distinguishes publishable from draft", () => {
  assert.equal(badgeFor("draft").isPublishable, false);
  assert.equal(badgeFor("verified").isPublishable, true);
});

// --- regression comparison ---

function verifiedRecord(overrides = {}) {
  return {
    labId: "false-sharing",
    variant: "shared",
    language: "java",
    harness: "jmh",
    metricKind: "scalar",
    unit: "ops/ms",
    direction: "higherIsBetter",
    statistic: { pointEstimate: 100000, error: 1000, errorKind: "ci99.9", statisticName: "mean", sampleCount: null },
    provenance: { rawArtifactPath: "results/false-sharing/abc/raw", sourceRevision: "abc1234", command: null, environmentRef: "env-1", importerVersion: "jmh-importer@1", capturedAt: "2026-01-01T00:00:00.000Z" },
    comparability: { buildMode: "release", datasetId: null, semanticsFixtureHash: null, architecture: "arm64" },
    evidenceMaturity: "verified",
    ...overrides,
  };
}

test("regression: no compatible verified history yields insufficient-history", () => {
  const result = compareToHistory(verifiedRecord({ evidenceMaturity: "draft" }), []);
  assert.equal(result.status, "insufficient-history");
});

test("regression: a 10% throughput drop beyond the default 5% threshold is a regression", () => {
  const baseline = verifiedRecord({ provenance: { ...verifiedRecord().provenance, capturedAt: "2026-01-01T00:00:00.000Z" } });
  const next = verifiedRecord({ statistic: { ...verifiedRecord().statistic, pointEstimate: 90000 } });
  const result = compareToHistory(next, [baseline]);
  assert.equal(result.status, "regression");
});

test("regression: a 10% throughput gain is an improvement, not a regression", () => {
  const baseline = verifiedRecord();
  const next = verifiedRecord({ statistic: { ...verifiedRecord().statistic, pointEstimate: 110000 } });
  const result = compareToHistory(next, [baseline]);
  assert.equal(result.status, "improvement");
});

test("regression: for a lowerIsBetter metric, a higher new value is a regression", () => {
  const baseline = verifiedRecord({ direction: "lowerIsBetter", unit: "ms", statistic: { ...verifiedRecord().statistic, pointEstimate: 1.0 } });
  const next = verifiedRecord({ direction: "lowerIsBetter", unit: "ms", statistic: { ...verifiedRecord().statistic, pointEstimate: 1.2 } });
  const result = compareToHistory(next, [baseline]);
  assert.equal(result.status, "regression");
});

test("regression: an incompatible historical record (different architecture) is not used as baseline", () => {
  const baseline = verifiedRecord({ comparability: { ...verifiedRecord().comparability, architecture: "x86_64" } });
  const next = verifiedRecord({ statistic: { ...verifiedRecord().statistic, pointEstimate: 50000 } });
  assert.equal(findBaseline(next, [baseline]), null);
});

test("regression: a per-unit threshold override changes the classification", () => {
  const baseline = verifiedRecord();
  const next = verifiedRecord({ statistic: { ...verifiedRecord().statistic, pointEstimate: 97000 } }); // 3% drop
  assert.equal(compareToHistory(next, [baseline]).status, "stable"); // within default 5%
  assert.equal(compareToHistory(next, [baseline], { "ops/ms": 0.01 }).status, "regression"); // 1% override
});

// --- legacy migration fidelity ---

test("legacy-data: every migrated record validates against the schema", () => {
  for (const labId of LEGACY_LAB_IDS) {
    for (const record of legacyResultsFor(labId)) {
      const { valid, errors } = validateResult(record);
      assert.equal(valid, true, `${labId}/${record.variant}: ${errors.join("; ")}`);
    }
  }
});

test("legacy-data: every migrated record is stamped legacy-unprovenanced with no raw artifact", () => {
  for (const labId of LEGACY_LAB_IDS) {
    for (const record of legacyResultsFor(labId)) {
      assert.equal(record.evidenceMaturity, "legacy-unprovenanced");
      assert.equal(record.provenance.rawArtifactPath, null);
    }
  }
});

// Drift guard: every transcribed pointEstimate must still appear verbatim in
// the benchmark.md it was transcribed from, so legacy-data.js cannot quietly
// diverge from the committed prose it claims to represent.
test("legacy-data: every pointEstimate appears verbatim in its lab's benchmark.md", () => {
  for (const labId of LEGACY_LAB_IDS) {
    const benchmarkMd = readFileSync(join(LABS_ROOT, labId, "benchmark.md"), "utf8");
    for (const record of legacyResultsFor(labId)) {
      const formatted = record.statistic.pointEstimate.toLocaleString("en-US");
      const bare = String(record.statistic.pointEstimate);
      const found = benchmarkMd.includes(formatted) || benchmarkMd.includes(bare);
      assert.ok(found, `${labId}/${record.variant}: pointEstimate ${record.statistic.pointEstimate} not found verbatim in benchmark.md`);
    }
  }
});

test("legacy-data: labs with no benchmark.md (memory-ordering, mesi) have no legacy records", () => {
  assert.deepEqual(legacyResultsFor("memory-ordering"), []);
  assert.deepEqual(legacyResultsFor("mesi"), []);
});
