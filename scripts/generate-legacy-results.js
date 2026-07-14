#!/usr/bin/env node
// Writes content/labs/<id>/legacy-results.json from the single source of
// truth (scripts/benchmark-platform/results/legacy-data.js) — plab-003 task
// 9's migration of pre-plab-003 benchmark.md numbers into the canonical
// schema. Deterministic: re-running with no data change produces byte
// identical output (docs/benchmark-results-migration.md).
import { writeFileSync } from "node:fs";
import { join } from "node:path";
import { LEGACY_LAB_IDS, legacyResultsFor } from "./benchmark-platform/results/legacy-data.js";
import { validateResult } from "./benchmark-platform/results/schema.js";

const LABS_ROOT = join(import.meta.dirname, "..", "content", "labs");

let written = 0;
for (const labId of LEGACY_LAB_IDS) {
  const records = legacyResultsFor(labId);
  for (const record of records) {
    const { valid, errors } = validateResult(record);
    if (!valid) {
      console.error(`generate-legacy-results: invalid record for ${labId}/${record.variant}:\n  ${errors.join("\n  ")}`);
      process.exit(1);
    }
  }
  const outPath = join(LABS_ROOT, labId, "legacy-results.json");
  writeFileSync(outPath, `${JSON.stringify(records, null, 2)}\n`);
  written += 1;
}

console.log(`generate-legacy-results: wrote legacy-results.json for ${written} lab(s)`);
