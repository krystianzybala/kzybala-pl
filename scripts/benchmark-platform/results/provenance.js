// Provenance and comparability validation (plab-003 task 5).
// Composes on top of the existing plab-002 comparability rules
// (scripts/benchmark-platform/comparability.js) rather than duplicating
// them: that module already encodes "reject or visibly flag debug builds,
// unequal datasets, unequal semantics, or missing warm-up/configuration
// metadata" for a Java-vs-Rust pair. This module adds the provenance-chain
// checks spec.md's "Provenance chain" requirement needs for a *single*
// record (not a pair): every displayed value must trace to a raw artifact,
// source revision, command, environment and importer version.
import { existsSync } from "node:fs";
import { validateResult } from "./schema.js";
import { validateComparability } from "../comparability.js";

// A short git hash (as produced by `git log --format=%h`) or the explicit
// "unversioned" sentinel (scripts/benchmark-platform/run-id.js's
// resolveImplementationRevision) are the only two legitimate values —
// anything else suggests a hand-typed placeholder.
const REVISION_PATTERN = /^[0-9a-f]{7,40}$/;

function validateSourceRevision(sourceRevision) {
  if (sourceRevision === "unversioned") return [];
  if (typeof sourceRevision === "string" && REVISION_PATTERN.test(sourceRevision)) return [];
  return [`provenance.sourceRevision "${sourceRevision}" is neither a git short/full hash nor "unversioned"`];
}

// Checks one record's provenance chain is actually followable, not merely
// shaped correctly (schema.js checks shape; this checks the chain resolves).
// `cwd` lets tests point at a fixture root instead of the real repo.
function validateProvenanceChain(record, { cwd = process.cwd() } = {}) {
  const errors = [];
  const { valid: schemaValid, errors: schemaErrors } = validateResult(record);
  if (!schemaValid) {
    return { valid: false, errors: schemaErrors };
  }

  const provenance = record.provenance;
  errors.push(...validateSourceRevision(provenance.sourceRevision));

  if (provenance.rawArtifactPath !== null) {
    const resolved = provenance.rawArtifactPath.startsWith("/") ? provenance.rawArtifactPath : `${cwd}/${provenance.rawArtifactPath}`;
    if (!existsSync(resolved)) {
      errors.push(`provenance.rawArtifactPath "${provenance.rawArtifactPath}" does not exist on disk`);
    }
  } else if (!["draft", "legacy-unprovenanced"].includes(record.evidenceMaturity)) {
    errors.push(`provenance.rawArtifactPath is null but evidenceMaturity is "${record.evidenceMaturity}" (only "draft"/"legacy-unprovenanced" may omit it)`);
  }

  if (!provenance.environmentRef && !["draft", "legacy-unprovenanced"].includes(record.evidenceMaturity)) {
    errors.push('provenance.environmentRef is missing (required for any evidenceMaturity above "draft"/"legacy-unprovenanced")');
  }

  return { valid: errors.length === 0, errors };
}

// Cross-language comparability for a Java/Rust record pair, reusing
// scripts/benchmark-platform/comparability.js's validateComparability and
// translating a canonical record's `comparability` block into the shape it
// expects.
function validateRecordPairComparability(javaRecord, rustRecord) {
  const toComparabilityInput = (record) => ({
    buildMode: record.comparability?.buildMode ?? null,
    datasetId: record.comparability?.datasetId ?? null,
    semanticsFixtureHash: record.comparability?.semanticsFixtureHash ?? null,
    warmup: record.comparability?.warmup ?? null,
  });
  return validateComparability(toComparabilityInput(javaRecord), toComparabilityInput(rustRecord));
}

export { validateProvenanceChain, validateRecordPairComparability, validateSourceRevision };
