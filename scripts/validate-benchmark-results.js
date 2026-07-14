#!/usr/bin/env node
// Content gate: rejects any committed result manifest that would let an
// unprovenanced or fabricated-evidence chart reach the site (plab-003 task
// 11; rewritten in the 2026-07-14 remediation pass). Rejects:
//   - any record that fails schema validation (shape, enums, cross-field
//     consistency, canonical-numeric-hash self-consistency),
//   - any record whose provenance hash references don't resolve or don't
//     match the actual file content under the approved artifacts root,
//   - duplicate result identities across the combined manifest set,
//   - a derivation cycle or missing link in any statistic.derivedFrom chain,
//   - a legacy record whose containing directory doesn't match its labId.
// It also reports the *derived* evidence-maturity distribution across
// everything it checked — never trusting a stored maturity label, because
// there isn't one anymore (see evidence-maturity.js).
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { validateResult, findDuplicateIdentities } from "./benchmark-platform/results/schema.js";
import { validateProvenanceChain, detectDerivationCycle, defaultArtifactsRoot } from "./benchmark-platform/results/provenance.js";
import { deriveMaturity } from "./benchmark-platform/results/evidence-maturity.js";

const ROOT = join(import.meta.dirname, "..");
const LABS_ROOT = join(ROOT, "content", "labs");
const ARTIFACTS_ROOT = defaultArtifactsRoot();
const MANIFEST_NAMES = ["legacy-results.json", "results-manifest.json"];

const errors = [];
const fail = (msg) => errors.push(msg);

function loadManifest(path) {
  try {
    return { records: JSON.parse(readFileSync(path, "utf8")) };
  } catch (err) {
    return { parseError: err.message };
  }
}

let manifestsChecked = 0;
const allRecords = []; // { record, label }
const byHash = new Map();

for (const labId of readdirSync(LABS_ROOT, { withFileTypes: true }).filter((e) => e.isDirectory()).map((e) => e.name)) {
  for (const manifestName of MANIFEST_NAMES) {
    const path = join(LABS_ROOT, labId, manifestName);
    if (!existsSync(path)) continue;
    manifestsChecked += 1;

    const { records, parseError } = loadManifest(path);
    if (parseError) {
      fail(`${labId}/${manifestName}: invalid JSON (${parseError})`);
      continue;
    }
    if (!Array.isArray(records)) {
      fail(`${labId}/${manifestName}: expected an array of result records`);
      continue;
    }

    for (const [index, record] of records.entries()) {
      const label = `${labId}/${manifestName}[${index}] (${record?.variant ?? "?"})`;

      const { valid: schemaValid, errors: schemaErrors } = validateResult(record);
      if (!schemaValid) {
        for (const err of schemaErrors) fail(`${label}: ${err}`);
        continue; // provenance/duplicate/cycle checks assume a schema-valid shape
      }

      if (record.labId !== labId) {
        fail(`${label}: record.labId "${record.labId}" does not match its containing directory "${labId}"`);
      }

      const { valid: provenanceValid, errors: provenanceErrors } = validateProvenanceChain(record, { artifactsRoot: ARTIFACTS_ROOT });
      if (!provenanceValid) {
        for (const err of provenanceErrors) fail(`${label}: ${err}`);
      }

      allRecords.push({ record, label });
      if (!byHash.has(record.provenance.canonicalResultHash)) byHash.set(record.provenance.canonicalResultHash, record);
    }
  }
}

for (const { identity, indices } of findDuplicateIdentities(allRecords.map((r) => r.record))) {
  const labels = indices.map((i) => allRecords[i].label).join(", ");
  fail(`duplicate result identity across manifests ("${identity}"): ${labels}`);
}

for (const { record, label } of allRecords) {
  if (record.statistic?.derivedFrom) {
    const cycle = detectDerivationCycle(record, (hash) => byHash.get(hash) ?? null);
    if (!cycle.ok) fail(`${label}: ${cycle.reason}`);
  }
}

const maturityCounts = {};
for (const { record } of allRecords) {
  const { level } = deriveMaturity(record, { artifactsRoot: ARTIFACTS_ROOT });
  maturityCounts[level] = (maturityCounts[level] ?? 0) + 1;
}

if (errors.length > 0) {
  console.error(`validate-benchmark-results: ${errors.length} problem(s) found across ${manifestsChecked} manifest(s):\n`);
  for (const err of errors) console.error(`  - ${err}`);
  process.exit(1);
}

const summary = Object.entries(maturityCounts).map(([level, count]) => `${level}=${count}`).join(", ");
console.log(`validate-benchmark-results: OK — ${allRecords.length} record(s) across ${manifestsChecked} manifest(s), all schema- and provenance-valid. Derived evidence maturity: ${summary || "(none)"}.`);
