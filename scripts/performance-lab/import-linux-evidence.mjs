#!/usr/bin/env node
// Canonical import for a native-Linux evidence run directory
// (results/<lab>/<run-id>/, produced by run-linux-evidence.sh and copied in
// by import-evidence.sh). Produces, per variant: canonical-jmh.json and
// canonical-perf-stat.json (plab-003 importers, schema-validated), plus a
// derived comparison.json at the run root — all computed from the raw
// artifacts, never typed in.
//
// Importing NEVER promotes evidence maturity: records start at the honest
// baseline (reproduction 0/1 completed, no reviewer), the perf importer's
// capability ceiling stays wherever capability-registry.js has it, and this
// script only *prints* the derived maturity so the reviewer can see it.
import { createHash } from "node:crypto";
import { existsSync, readFileSync, writeFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, resolve } from "node:path";
import { importJmhResults } from "../benchmark-platform/results/jmh-importer.js";
import { importPerfCounterCsv } from "../benchmark-platform/results/perf-counter-importer.js";
import { validateResult } from "../benchmark-platform/results/schema.js";
import { deriveMaturity } from "../benchmark-platform/results/evidence-maturity.js";

const RUN_DIR = resolve(process.argv[2] ?? "");
if (!RUN_DIR || !existsSync(join(RUN_DIR, "evidence-manifest.json"))) {
  console.error("usage: import-linux-evidence.mjs <results/<lab>/<run-id> directory>");
  process.exit(2);
}
const REPO_ROOT = resolve(import.meta.dirname, "..", "..");

const readJson = (path) => JSON.parse(readFileSync(path, "utf8"));
const sha256 = (path) => createHash("sha256").update(readFileSync(path)).digest("hex");
const repoRel = (path) => relative(REPO_ROOT, path);
const rawRef = (path) => ({ path: repoRel(path), sha256: sha256(path) });
const hashRef = (path) => ({ ref: repoRel(path), hash: sha256(path) });

const manifest = readJson(join(RUN_DIR, "evidence-manifest.json"));
const environment = readJson(join(RUN_DIR, "environment.json"));
const profile = readJson(join(RUN_DIR, "benchmark-profile.json"));
const correctness = readJson(join(RUN_DIR, "correctness.json"));

// A rejected run is diagnostic only — it can never become canonical
// evidence, enter regression history, or change public lab status.
const statusPath = join(RUN_DIR, "run-status.json");
const runStatus = existsSync(statusPath) ? readJson(statusPath) : null;
if (!runStatus || runStatus.runStatus !== "collected" || runStatus.canonicalEvidenceEligible !== true) {
  console.error(
    `import-linux-evidence: run-status is "${runStatus?.runStatus ?? "missing"}"`
      + (runStatus?.rejectionReason ? ` (${runStatus.rejectionReason})` : "")
      + " — rejected/partial runs are diagnostic only and are never imported as canonical evidence");
  process.exit(1);
}

if (correctness.status !== "passed") {
  console.error(`import-linux-evidence: correctness gate status is "${correctness.status}" — refusing to import measurements collected without a passing gate`);
  process.exit(1);
}

// Worker placement is the blocking placement evidence: every variant with
// placement data must show zero violations; a variant without placement
// data imports with an explicit warning (which alone blocks "verified").
const placementWarnings = {};
for (const variant of Object.keys(manifest.variants ?? {})) {
  const placementPath = join(RUN_DIR, variant, "worker-placement.json");
  if (!existsSync(placementPath)) {
    placementWarnings[variant] = ["worker placement evidence unavailable — workers were not pinned for this run"];
    continue;
  }
  const placement = readJson(placementPath);
  if ((placement.violations ?? []).length > 0) {
    console.error(`import-linux-evidence: ${variant}/worker-placement.json records blocking violations:\n  ${placement.violations.join("\n  ")}`);
    process.exit(1);
  }
  placementWarnings[variant] = [];
}

// "native-controlled" is only claimed when the captured environment shows a
// pinned performance governor on both selected CPUs; anything else stays
// native-uncontrolled. Turbo/SMT state is captured and reviewable but does
// not upgrade the label automatically.
const controlled =
  environment.governorCpuA === "performance" && environment.governorCpuB === "performance";
const environmentLabel = controlled ? "native-controlled" : "native-uncontrolled";

const commonMeta = {
  labId: manifest.labId,
  sourceRevision: manifest.sourceCommit,
  dirty: manifest.dirtyTree === true,
  diffHash: manifest.diffHash ?? null,
  capturedAt: environment.capturedAt,
  environmentManifest: hashRef(join(RUN_DIR, "environment.json")),
  toolchainManifest: hashRef(join(RUN_DIR, "toolchain.json")),
  benchmarkProfile: hashRef(join(RUN_DIR, "benchmark-profile.json")),
  correctnessGate: hashRef(join(RUN_DIR, "correctness.json")),
  ...(existsSync(join(REPO_ROOT, "content/labs", manifest.labId, "code/fixtures"))
    ? { semanticFixture: hashRef(join(REPO_ROOT, "content/labs", manifest.labId, "code/fixtures",
        readdirSync(join(REPO_ROOT, "content/labs", manifest.labId, "code/fixtures"))[0])) }
    : {}),
  evidence: {
    correctness: "passed",
    environment: environmentLabel,
    profiling: "present",
    comparability: "not-applicable",
    reproduction: { required: 1, completed: 0 },
  },
  architecture: environment.kernel,
};

const VARIANTS = Object.keys(manifest.variants ?? {});
if (VARIANTS.length === 0) {
  console.error("import-linux-evidence: manifest lists no variants");
  process.exit(1);
}
const allRecords = [];
const jmhByVariant = {};

for (const variant of VARIANTS) {
  const vdir = join(RUN_DIR, variant);
  const c2cReport = join(vdir, "perf-c2c-report.txt");
  const hasC2c = existsSync(c2cReport);
  const placementPath = join(vdir, "worker-placement.json");
  const variantMeta = {
    ...commonMeta,
    evidence: {
      ...commonMeta.evidence,
      profiling: hasC2c ? "present" : "absent",
      warnings: placementWarnings[variant] ?? [],
    },
  };

  // --- JMH -----------------------------------------------------------------
  const jmhPath = join(vdir, "jmh.json");
  const jmhRecords = importJmhResults(readJson(jmhPath), {
    ...variantMeta,
    rawArtifact: rawRef(jmhPath),
    profilingArtifact: hasC2c ? hashRef(c2cReport) : existsSync(placementPath) ? hashRef(placementPath) : undefined,
    command: `run-linux-evidence.sh ${manifest.labId} --profile ${manifest.profile} (variant ${variant}, cpus ${profile.placement.cpus})`,
  });
  jmhByVariant[variant] = { records: jmhRecords, document: readJson(jmhPath) };
  writeFileSync(join(vdir, "canonical-jmh.json"), `${JSON.stringify(jmhRecords, null, 2)}\n`);
  allRecords.push(...jmhRecords.map((record) => ({ record, label: `${variant}/jmh` })));

  // --- perf stat (every repetition, kept separate) ---------------------------
  const counterRecords = [];
  const statFiles = [
    { csv: "perf-stat.csv", jmh: "perf-stat-jmh.json", repetition: 1 },
    { csv: "perf-stat-r2.csv", jmh: "perf-stat-jmh-r2.json", repetition: 2 },
    { csv: "perf-stat-r3.csv", jmh: "perf-stat-jmh-r3.json", repetition: 3 },
  ];
  for (const { csv, repetition } of statFiles) {
    const csvPath = join(vdir, csv);
    if (!existsSync(csvPath)) {
      console.error(`import-linux-evidence: missing ${variant}/${csv}`);
      process.exit(1);
    }
    const records = importPerfCounterCsv(readFileSync(csvPath, "utf8"), {
      ...variantMeta,
      variant,
      language: "java",
      parameters: { scenario: variant, repetition: String(repetition) },
      rawArtifact: rawRef(csvPath),
      profilingArtifact: hasC2c ? hashRef(c2cReport) : undefined,
      command: `perf stat -x, (single-fork JMH inside; exact command in benchmark-profile.json) — variant ${variant}, repetition ${repetition}`,
    });
    counterRecords.push(...records);
    allRecords.push(...records.map((record) => ({ record, label: `${variant}/${csv}` })));
  }
  writeFileSync(join(vdir, "canonical-perf-stat.json"), `${JSON.stringify(counterRecords, null, 2)}\n`);
}

// --- Schema validation -------------------------------------------------------
let invalid = 0;
for (const { record, label } of allRecords) {
  const { valid, errors } = validateResult(record);
  if (!valid) {
    invalid += 1;
    console.error(`INVALID ${label}:\n  ${errors.join("\n  ")}`);
  }
}
if (invalid > 0) process.exit(1);

// --- Derived comparison -------------------------------------------------------
// All ratios are computed from the raw imported values at full double
// precision; canonical inputs are referenced, formulas are recorded, and
// display rounding is separate (displayRounded). No causation is claimed
// here: comparison.json carries numbers, the lab page carries the argument,
// and the argument requires JMH + c2c + correctness + placement together.
function groupOpsPerSecond(jmhDocumentEntry) {
  // Group primary score is the combined ops/ms of both writer threads.
  return jmhDocumentEntry.primaryMetric.score * 1000;
}

function totalMeasuredOps(jmhDocumentEntry) {
  // operations ≈ mean rate × total measured time of the same execution:
  // score(ops/ms) × measurementIterations × measurementTime(ms) × forks.
  const timeMs = parseTimeMs(jmhDocumentEntry.measurementTime);
  return {
    value:
      jmhDocumentEntry.primaryMetric.score *
      timeMs *
      jmhDocumentEntry.measurementIterations *
      jmhDocumentEntry.forks,
    formula: "score(ops/ms) × measurementTime(ms) × measurementIterations × forks",
  };
}

function parseTimeMs(text) {
  const m = /^([\d.]+)\s*(ms|s)$/.exec(String(text).trim());
  if (!m) throw new Error(`unparseable JMH time "${text}"`);
  return m[2] === "s" ? Number(m[1]) * 1000 : Number(m[1]);
}

function counterValue(csvPath, event) {
  const line = readFileSync(csvPath, "utf8")
    .split("\n")
    .find((l) => l.split(",")[2] === event);
  if (!line) return null;
  const value = line.split(",")[0].trim();
  return value === "<not counted>" || value === "<not supported>" ? null : Number(value);
}

// perf c2c --stdio report parsing is best-effort: formats differ across
// perf versions. Unparseable fields become null with a note — they are
// never invented.
function parseC2cReport(path) {
  if (!existsSync(path)) return { available: false, note: "report missing" };
  const text = readFileSync(path, "utf8");
  const grab = (patterns) => {
    for (const pattern of patterns) {
      const m = pattern.exec(text);
      if (m) return Number(m[1].replace(/[,\s]/g, ""));
    }
    return null;
  };
  const result = {
    available: true,
    totalRecords: grab([/Total records\s*:\s*([\d,]+)/i]),
    totalLoads: grab([/Total loads\s*:\s*([\d,]+)/i]),
    totalStores: grab([/Total stores\s*:\s*([\d,]+)/i]),
    localHitm: grab([/Local HITM\s*:?\s*([\d,]+)/i, /Lcl\s*HITM\s*:?\s*([\d,]+)/i]),
    remoteHitm: grab([/Remote HITM\s*:?\s*([\d,]+)/i, /Rmt\s*HITM\s*:?\s*([\d,]+)/i]),
    peerSnoop: grab([/Load\s+Peer\s*:?\s*([\d,]+)/i, /peer\s*:?\s*([\d,]+)/i]),
  };
  result.parseNote =
    "fields parsed from perf c2c --stdio text; null means the field was not found in this perf version's format — consult perf-c2c-report.txt directly; the binary perf-c2c.data remains the primary evidence";
  return result;
}

const comparison = {
  generatedBy: "scripts/performance-lab/import-linux-evidence.mjs",
  runId: manifest.runId,
  inputs: {},
  formulas: {
    throughputRatio: "paddedOperationsPerSecond / sharedOperationsPerSecond (JMH group score, both writers combined)",
    operationsPerSecond: "JMH primaryMetric.score (ops/ms) × 1000",
    cyclesPerOperation: "cycles / measuredOperations, per perf-stat repetition, operations from the matched perf-stat JMH execution",
    instructionsPerOperation: "instructions / measuredOperations (same matching)",
    cacheMissesPerMillionOperations: "cache-misses / measuredOperations × 1,000,000 (same matching)",
    hitmPerMillionOperations: "(localHitm + remoteHitm) / measuredOperations(perf-c2c JMH execution unavailable — uses mean of perf-stat executions' operations) × 1,000,000; null when HITM could not be parsed",
    measuredOperations: "score(ops/ms) × measurementTime(ms) × measurementIterations × forks (per execution)",
  },
  variants: {},
  caution:
    "Throughput alone does not establish causation. The false-sharing conclusion additionally requires the perf c2c HITM evidence, equivalent correctness results, and stable placement/environment metadata — see docs/linux-evidence-runner.md review checklist.",
};

for (const variant of VARIANTS) {
  const vdir = join(RUN_DIR, variant);
  const evidenceEntry = jmhByVariant[variant].document[0];
  const opsPerSecond = groupOpsPerSecond(evidenceEntry);

  const repetitions = [];
  const statFiles = [
    { csv: "perf-stat.csv", jmh: "perf-stat-jmh.json" },
    { csv: "perf-stat-r2.csv", jmh: "perf-stat-jmh-r2.json" },
    { csv: "perf-stat-r3.csv", jmh: "perf-stat-jmh-r3.json" },
  ];
  for (const { csv, jmh } of statFiles) {
    const jmhPath = join(vdir, jmh);
    const csvPath = join(vdir, csv);
    if (!existsSync(jmhPath)) {
      repetitions.push({ csv, note: "matched JMH json missing — per-operation ratios unavailable for this repetition" });
      continue;
    }
    const entry = readJson(jmhPath)[0];
    const ops = totalMeasuredOps(entry);
    const cycles = counterValue(csvPath, "cycles");
    const instructions = counterValue(csvPath, "instructions");
    const cacheMisses = counterValue(csvPath, "cache-misses");
    repetitions.push({
      csv,
      measuredOperations: ops.value,
      cycles,
      instructions,
      cacheMisses,
      cyclesPerOperation: cycles === null ? null : cycles / ops.value,
      instructionsPerOperation: instructions === null ? null : instructions / ops.value,
      cacheMissesPerMillionOperations: cacheMisses === null ? null : (cacheMisses / ops.value) * 1_000_000,
    });
  }

  const c2c = parseC2cReport(join(vdir, "perf-c2c-report.txt"));
  const meanStatOps =
    repetitions.filter((r) => typeof r.measuredOperations === "number").reduce((a, r) => a + r.measuredOperations, 0) /
      Math.max(1, repetitions.filter((r) => typeof r.measuredOperations === "number").length) || null;
  const hitmTotal = c2c.available && (c2c.localHitm !== null || c2c.remoteHitm !== null)
    ? (c2c.localHitm ?? 0) + (c2c.remoteHitm ?? 0)
    : null;

  comparison.inputs[variant] = {
    jmh: rawRef(join(vdir, "jmh.json")),
    canonicalJmh: rawRef(join(vdir, "canonical-jmh.json")),
    canonicalPerfStat: rawRef(join(vdir, "canonical-perf-stat.json")),
    perfC2cReport: existsSync(join(vdir, "perf-c2c-report.txt")) ? rawRef(join(vdir, "perf-c2c-report.txt")) : null,
  };
  comparison.variants[variant] = {
    operationsPerSecond: opsPerSecond,
    perfStatRepetitions: repetitions,
    perfC2c: c2c,
    hitmPerMillionOperations: hitmTotal === null || meanStatOps === null ? null : (hitmTotal / meanStatOps) * 1_000_000,
  };
}

// Headline ratio only where the lab's variant pair defines one (the
// false-sharing shared/padded contract); other labs get per-variant
// metrics without an invented cross-variant headline.
if (comparison.variants.shared && comparison.variants.padded) {
  comparison.throughputRatio =
    comparison.variants.padded.operationsPerSecond / comparison.variants.shared.operationsPerSecond;
  comparison.displayRounded = {
    throughputRatio: Number(comparison.throughputRatio.toFixed(2)),
    sharedOpsPerSecond: Number(comparison.variants.shared.operationsPerSecond.toPrecision(4)),
    paddedOpsPerSecond: Number(comparison.variants.padded.operationsPerSecond.toPrecision(4)),
  };
} else {
  comparison.throughputRatio = null;
  comparison.displayRounded = Object.fromEntries(
    Object.entries(comparison.variants).map(([name, v]) => [name, Number(v.operationsPerSecond.toPrecision(4))]),
  );
}

writeFileSync(join(RUN_DIR, "comparison.json"), `${JSON.stringify(comparison, null, 2)}\n`);

// --- Manifest + hashes ---------------------------------------------------------
manifest.canonical = {
  pendingImport: false,
  importedAt: new Date().toISOString(),
  artifacts: VARIANTS.flatMap((v) => [`${v}/canonical-jmh.json`, `${v}/canonical-perf-stat.json`]).concat(["comparison.json"]),
};
writeFileSync(join(RUN_DIR, "evidence-manifest.json"), `${JSON.stringify(manifest, null, 2)}\n`);

// Recompute SHA256SUMS to cover the canonical artifacts too.
function* walk(dir) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) yield* walk(full);
    else yield full;
  }
}
const sums = [...walk(RUN_DIR)]
  .filter((f) => !f.endsWith("/SHA256SUMS") && f !== join(RUN_DIR, "SHA256SUMS"))
  .sort()
  .map((f) => `${sha256(f)}  ./${relative(RUN_DIR, f)}`)
  .join("\n");
writeFileSync(join(RUN_DIR, "SHA256SUMS"), `${sums}\n`);

// --- Derived maturity (printed for the reviewer; never stored) -----------------
console.log(`import-linux-evidence: ${allRecords.length} canonical records, all schema-valid`);
const sample = allRecords[0].record;
const maturity = deriveMaturity(sample, { artifactsRoot: REPO_ROOT });
console.log(`derived evidence maturity (first record, artifactsRoot=repo): ${maturity.level}`);
if (!maturity.isVerified) {
  console.log(`not verified — unmet: ${maturity.reasons.join("; ") || "n/a"}`);
}
console.log("Reminder: verified status requires independent reproduction, human review, and a maintainer-approved importer capability — none of which this import grants.");
