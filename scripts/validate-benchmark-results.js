#!/usr/bin/env node
// Content gate: rejects any committed result manifest that would let an
// unprovenanced chart reach the site (plab-003 task 11). spec.md's "No
// manual numbers" requirement: a chart's values must come from validated
// result artifacts, not hand-entered numbers outside explicitly labeled
// examples. This scans every `content/labs/*/legacy-results.json` (today's
// only committed result manifest — docs/benchmark-results-migration.md) and
// any future `content/labs/*/results-manifest.json`, validating schema
// shape and provenance chain for each record it finds.
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { validateResult } from "./benchmark-platform/results/schema.js";
import { validateProvenanceChain } from "./benchmark-platform/results/provenance.js";

const ROOT = join(import.meta.dirname, "..");
const LABS_ROOT = join(ROOT, "content", "labs");
const MANIFEST_NAMES = ["legacy-results.json", "results-manifest.json"];

const errors = [];
const fail = (msg) => errors.push(msg);

function loadManifest(path) {
  try {
    return JSON.parse(readFileSync(path, "utf8"));
  } catch (err) {
    return { parseError: err.message };
  }
}

let manifestsChecked = 0;
let recordsChecked = 0;

for (const labId of readdirSync(LABS_ROOT, { withFileTypes: true }).filter((e) => e.isDirectory()).map((e) => e.name)) {
  for (const manifestName of MANIFEST_NAMES) {
    const path = join(LABS_ROOT, labId, manifestName);
    if (!existsSync(path)) continue;
    manifestsChecked += 1;

    const manifest = loadManifest(path);
    if (manifest.parseError) {
      fail(`${labId}/${manifestName}: invalid JSON (${manifest.parseError})`);
      continue;
    }
    if (!Array.isArray(manifest)) {
      fail(`${labId}/${manifestName}: expected an array of result records`);
      continue;
    }

    for (const [index, record] of manifest.entries()) {
      recordsChecked += 1;
      const label = `${labId}/${manifestName}[${index}] (${record?.variant ?? "?"})`;

      const { valid: schemaValid, errors: schemaErrors } = validateResult(record);
      if (!schemaValid) {
        for (const err of schemaErrors) fail(`${label}: ${err}`);
        continue; // provenance check assumes a schema-valid shape
      }

      if (record.labId !== labId) {
        fail(`${label}: record.labId "${record.labId}" does not match its containing directory "${labId}"`);
      }

      const { valid: provenanceValid, errors: provenanceErrors } = validateProvenanceChain(record, { cwd: ROOT });
      if (!provenanceValid) {
        for (const err of provenanceErrors) fail(`${label}: ${err}`);
      }
    }
  }
}

if (errors.length > 0) {
  console.error(`validate-benchmark-results: ${errors.length} problem(s) found across ${manifestsChecked} manifest(s):\n`);
  for (const err of errors) console.error(`  - ${err}`);
  process.exit(1);
}

console.log(`validate-benchmark-results: OK — ${recordsChecked} record(s) across ${manifestsChecked} manifest(s), all schema- and provenance-valid.`);
