// Linux `perf stat` counter importer (plab-003 task 4).
// Parses the machine-readable CSV form of `perf stat -x,<sep> ...` output
// (documented in `man perf-stat`: one line per counted event, fields
// `value,unit,event,time_running,percent_running,metric_value,metric_unit`)
// into canonical "counter" records.
//
// Honesty note (docs/benchmark-results-importers.md): this repository's
// benchmark hosts to date are macOS/arm64 (docs/benchmark-platform-inventory.md),
// where `perf` does not exist — this parser is implemented strictly against
// the documented `perf stat` CSV format and exercised only against a
// synthetic fixture (scripts/benchmark-platform/results/__fixtures__/
// perf-stat-sample.csv), not against a live capture. No lab in this
// repository publishes a perf-counter number yet; do not cite this
// importer's output as measured evidence until it has been run against a
// real `perf stat -x,` capture on real Linux hardware.
import { SCHEMA_VERSION } from "./schema.js";

const IMPORTER_VERSION = 1;

function parseLine(line, separator) {
  const fields = line.split(separator);
  const [rawValue, unit, event, timeRunningNs, percentRunning] = fields;
  const value = rawValue === "<not counted>" || rawValue === "<not supported>" ? null : Number(rawValue);
  return {
    event: event?.trim() || null,
    value: Number.isFinite(value) ? value : null,
    unit: unit?.trim() || null,
    timeRunningNs: timeRunningNs != null && timeRunningNs !== "" ? Number(timeRunningNs) : null,
    percentRunning: percentRunning != null && percentRunning !== "" ? Number(percentRunning) : null,
    notCounted: rawValue === "<not counted>",
    notSupported: rawValue === "<not supported>",
  };
}

// `csvText` is the raw stdout of `perf stat -x<separator> -e <events> <cmd>`.
// Comment lines (perf prefixes informational lines with "#") are skipped.
// Returns one canonical counter record per parsed event line. Fails fast if
// no event line parses at all — an all-comment input is not a valid capture.
function importPerfCounterCsv(csvText, meta, separator = ",") {
  if (!meta || !meta.labId || !meta.variant) {
    throw new Error("perf-counter import requires meta.labId and meta.variant");
  }
  const lines = String(csvText)
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.length > 0 && !line.startsWith("#"));

  const parsed = lines.map((line) => parseLine(line, separator)).filter((row) => row.event !== null);

  if (parsed.length === 0) {
    throw new Error("perf-counter import: no parseable event line found in input");
  }

  return parsed.map((row) => ({
    schemaVersion: SCHEMA_VERSION,
    labId: meta.labId,
    variant: meta.variant,
    language: meta.language ?? null,
    harness: "perf-stat",
    metricKind: "counter",
    unit: row.unit,
    statistic: {
      value: row.value,
      event: row.event,
      notCounted: row.notCounted,
      notSupported: row.notSupported,
      percentRunning: row.percentRunning,
    },
    provenance: {
      rawArtifactPath: meta.rawArtifactPath ?? null,
      sourceRevision: meta.sourceRevision ?? "unversioned",
      command: meta.command ?? null,
      environmentRef: meta.environmentRef ?? null,
      importerVersion: `perf-counter-importer@${IMPORTER_VERSION}`,
      capturedAt: meta.capturedAt ?? null,
    },
    comparability: {
      datasetId: meta.datasetId ?? null,
      semanticsFixtureHash: meta.semanticsFixtureHash ?? null,
    },
    evidenceMaturity: meta.evidenceMaturity ?? "draft",
  }));
}

export { IMPORTER_VERSION, importPerfCounterCsv };
