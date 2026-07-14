// Linux `perf stat` counter importer (plab-003 task 4; rewritten in the
// 2026-07-14 remediation pass). The v1 version only ever destructured 5 of
// the 7 documented `perf stat -x,` CSV fields — `metric_value`/`metric_unit`
// (perf's own derived-metric column, e.g. "insn per cycle") were silently
// dropped even though the bundled fixture contains a row that has them, and
// malformed/unparseable rows were silently filtered out rather than
// rejected. This version parses the full documented field set, rejects an
// unparseable non-comment row instead of dropping it, and — most
// importantly — this importer's IMPORTER_CAPABILITY stays "fixture-only"
// (capability-registry.js) no matter how complete its field coverage gets:
// it has still only ever been exercised against a synthetic fixture, never
// a live `perf stat` capture on real Linux hardware, and nothing in this
// change fabricates that validation.
import { SCHEMA_ID, SCHEMA_VERSION } from "./schema.js";
import { toCanonicalNumber } from "./numeric.js";
import { buildProvenance, buildEvidence } from "./record-builder.js";
import { IMPORTER_CAPABILITIES } from "./capability-registry.js";

const IMPORTER_NAME = "perf-counter-importer";
const IMPORTER_VERSION = 2;
const IMPORTER_CAPABILITY = IMPORTER_CAPABILITIES[IMPORTER_NAME];

// `man perf-stat`'s documented `-x<sep>` CSV field order. A repeated-run
// capture (`perf stat -r N -x,`) appends one more field: the counter's
// relative standard deviation across runs, as a bare percentage number.
const CSV_FIELDS = ["value", "unit", "event", "timeRunningNs", "percentRunning", "metricValue", "metricUnit", "stddevPercent"];

function parseNumericField(text) {
  if (text == null || text === "") return null;
  const n = Number(text);
  return Number.isFinite(n) ? n : null;
}

function parseLine(line, separator, lineNumber) {
  const parts = line.split(separator).map((f) => f.trim());
  const row = {};
  CSV_FIELDS.forEach((name, index) => {
    row[name] = parts[index] ?? null;
  });

  if (!row.event) {
    throw new Error(`perf-counter import: line ${lineNumber} has no "event" field and is not a comment ("${line}") — rejecting rather than silently dropping it`);
  }

  const notCounted = row.value === "<not counted>";
  const notSupported = row.value === "<not supported>";
  if (!notCounted && !notSupported && parseNumericField(row.value) === null) {
    throw new Error(`perf-counter import: line ${lineNumber} ("${line}") has an unparseable value field "${row.value}" (expected a number, "<not counted>" or "<not supported>")`);
  }

  return {
    event: row.event,
    rawCountText: row.value,
    value: notCounted || notSupported ? null : parseNumericField(row.value),
    unit: row.unit === "" || row.unit === null ? "count" : row.unit,
    notCounted,
    notSupported,
    timeRunningNs: parseNumericField(row.timeRunningNs),
    percentRunning: parseNumericField(row.percentRunning),
    metricValue: parseNumericField(row.metricValue),
    metricUnit: row.metricUnit || null,
    stddevPercent: parseNumericField(row.stddevPercent),
  };
}

// `csvText` is the raw stdout of `perf stat -x<separator> -e <events> <cmd>`
// (optionally `-r N` for repeated runs). Comment lines (perf prefixes
// informational lines with "#") are skipped; every other non-empty line
// must parse or the whole import fails — there is no silent partial import.
function importPerfCounterCsv(csvText, meta, separator = ",") {
  if (!meta || !meta.labId || !meta.variant) {
    throw new Error("perf-counter import requires meta.labId and meta.variant");
  }
  const lines = String(csvText).split("\n").map((line, index) => ({ line: line.trim(), lineNumber: index + 1 })).filter(({ line }) => line.length > 0 && !line.startsWith("#"));

  if (lines.length === 0) {
    throw new Error("perf-counter import: no parseable event line found in input");
  }

  const parsed = lines.map(({ line, lineNumber }) => parseLine(line, separator, lineNumber));

  const seen = new Set();
  for (const row of parsed) {
    if (seen.has(row.event)) {
      throw new Error(`perf-counter import: duplicate event "${row.event}" in the same capture — duplicate metric identities fail fast`);
    }
    seen.add(row.event);
  }

  return parsed.map((row) => {
    const statistic = {
      event: row.event,
      value: row.value === null ? null : toCanonicalNumber(row.value),
      notCounted: row.notCounted,
      notSupported: row.notSupported,
      rawCountText: row.rawCountText,
      timeRunningNs: row.timeRunningNs === null ? null : toCanonicalNumber(row.timeRunningNs),
      percentRunning: row.percentRunning === null ? null : toCanonicalNumber(row.percentRunning),
      runsAggregated: Number.isInteger(meta.runsAggregated) && meta.runsAggregated >= 1 ? meta.runsAggregated : null,
      stddevPercent: row.stddevPercent === null ? null : toCanonicalNumber(row.stddevPercent),
      derivedMetric: row.metricValue === null || !row.metricUnit ? null : { value: toCanonicalNumber(row.metricValue), unit: row.metricUnit },
    };
    return {
      schemaId: SCHEMA_ID,
      schemaVersion: SCHEMA_VERSION,
      labId: meta.labId,
      variant: meta.variant,
      language: meta.language ?? null,
      harness: "perf-stat",
      metricKind: "counter",
      unit: row.unit,
      direction: null,
      mode: null,
      parameters: meta.parameters && typeof meta.parameters === "object" ? { ...meta.parameters } : {},
      statistic,
      provenance: buildProvenance(statistic, meta, `${IMPORTER_NAME}@${IMPORTER_VERSION}`),
      evidence: buildEvidence(meta, IMPORTER_CAPABILITY),
      comparability: {
        buildMode: null,
        threads: null,
        forks: null,
        warmup: null,
        measurement: null,
        datasetId: meta.datasetId ?? null,
        semanticsFixtureHash: meta.semanticsFixtureHash ?? null,
        architecture: meta.architecture ?? null,
      },
    };
  });
}

export { IMPORTER_NAME, IMPORTER_VERSION, IMPORTER_CAPABILITY, CSV_FIELDS, importPerfCounterCsv };
