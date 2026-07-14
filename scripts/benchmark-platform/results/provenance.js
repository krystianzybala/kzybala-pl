// Provenance chain verification (plab-003 task 5; rewritten in the
// 2026-07-14 remediation pass). The v1 version of this module only checked
// that a raw-artifact path *existed on disk* — any existing file anywhere
// on the filesystem satisfied it (confirmed exploitable with
// "../../../../etc/hosts" during the audit), and no other provenance link
// (environment/toolchain/dataset/profile/correctness-gate/profiling/render)
// was ever content-hashed at all. This version:
//   - resolves every artifact reference against one approved root and
//     rejects anything that traverses or symlinks outside it,
//   - content-hashes every artifact it accepts and compares against the
//     hash recorded on the record (hash-mismatch detection),
//   - treats a record's own measured statistic as a hashed, tamper-evident
//     value (schema.js verifies provenance.canonicalResultHash on every
//     validateResult call),
//   - exposes cycle detection for derived-metric chains.
import { existsSync, readFileSync, realpathSync, statSync } from "node:fs";
import { isAbsolute, resolve, sep } from "node:path";
import { execFileSync } from "node:child_process";
import { validateResult, HASH_REF_FIELDS, isPlainObject } from "./schema.js";
import { sha256File } from "./canonical-hash.js";
import { validateComparability } from "../comparability.js";

const MAX_ARTIFACT_BYTES = 50 * 1024 * 1024; // 50 MiB — generous for JMH/Criterion JSON or a perf capture, small enough to reject an accidental binary/log dump.

function defaultArtifactsRoot() {
  return resolve(import.meta.dirname, "artifacts");
}

// A short (>=7 hex) or full git hash, or the explicit "unversioned"
// sentinel (run-id.js's resolveImplementationRevision) — anything else
// suggests a hand-typed placeholder.
const REVISION_PATTERN = /^[0-9a-f]{7,40}$/;

function validateSourceRevision(revision) {
  if (revision === "unversioned") return [];
  if (typeof revision === "string" && REVISION_PATTERN.test(revision)) return [];
  return [`provenance.sourceCommit.revision "${revision}" is neither a git short/full hash nor "unversioned"`];
}

// Resolves `relativePath` against `root`, rejecting:
//   - absolute paths,
//   - lexical ".." traversal outside root,
//   - symlinks (inside the root) whose real target escapes the root,
//   - non-regular files (directories, devices, sockets, ...),
//   - anything over MAX_ARTIFACT_BYTES.
// Never throws — returns { ok: false, reason } for every rejection so a
// caller can report *why* without a try/catch around filesystem races.
function resolveWithinRoot(relativePath, root) {
  if (typeof relativePath !== "string" || relativePath.length === 0) {
    return { ok: false, reason: "artifact path must be a non-empty string" };
  }
  if (isAbsolute(relativePath)) {
    return { ok: false, reason: `artifact path must be relative to the approved artifacts root, got an absolute path: "${relativePath}"` };
  }
  const rootReal = existsSync(root) ? realpathSync(root) : resolve(root);
  const lexicallyResolved = resolve(rootReal, relativePath);
  if (lexicallyResolved !== rootReal && !lexicallyResolved.startsWith(rootReal + sep)) {
    return { ok: false, reason: `artifact path "${relativePath}" resolves outside the approved artifacts root (path traversal rejected)` };
  }
  if (!existsSync(lexicallyResolved)) {
    return { ok: false, reason: `artifact path "${relativePath}" does not exist under the approved artifacts root` };
  }
  const real = realpathSync(lexicallyResolved);
  if (real !== rootReal && !real.startsWith(rootReal + sep)) {
    return { ok: false, reason: `artifact path "${relativePath}" is a symlink that escapes the approved artifacts root` };
  }
  const stat = statSync(real);
  if (!stat.isFile()) {
    return { ok: false, reason: `artifact path "${relativePath}" is not a regular file (rejecting directories/devices/sockets/etc.)` };
  }
  if (stat.size > MAX_ARTIFACT_BYTES) {
    return { ok: false, reason: `artifact path "${relativePath}" is ${stat.size} bytes, exceeding the ${MAX_ARTIFACT_BYTES}-byte limit` };
  }
  return { ok: true, realPath: real, size: stat.size };
}

function hashArtifactFile(relativePath, { artifactsRoot = defaultArtifactsRoot() } = {}) {
  const resolved = resolveWithinRoot(relativePath, artifactsRoot);
  if (!resolved.ok) return resolved;
  return { ok: true, sha256: sha256File(readFileSync(resolved.realPath)), realPath: resolved.realPath };
}

function verifyRawArtifact(rawArtifact, opts = {}) {
  if (rawArtifact === null) return { ok: true, checked: false };
  const hashed = hashArtifactFile(rawArtifact.path, opts);
  if (!hashed.ok) return { ok: false, reason: `provenance.rawArtifact: ${hashed.reason}` };
  if (hashed.sha256 !== rawArtifact.sha256) {
    return { ok: false, reason: `provenance.rawArtifact content hash mismatch (recorded ${rawArtifact.sha256}, actual ${hashed.sha256}) — the file changed after import` };
  }
  return { ok: true, checked: true };
}

function verifyHashRefField(fieldValue, name, opts = {}) {
  if (fieldValue.hash === null) {
    return { ok: true, checked: false, complete: false };
  }
  if (fieldValue.ref === null) {
    // A hash with nothing to hash is a missing link, not a verified one.
    return { ok: false, reason: `provenance.${name}.hash is set but .ref is null (missing link — cannot verify a hash with no artifact reference)` };
  }
  const hashed = hashArtifactFile(fieldValue.ref, opts);
  if (!hashed.ok) return { ok: false, reason: `provenance.${name}: ${hashed.reason}` };
  if (fieldValue.hash !== hashed.sha256) {
    return { ok: false, reason: `provenance.${name} content hash mismatch (recorded ${fieldValue.hash}, actual ${hashed.sha256})` };
  }
  return { ok: true, checked: true, complete: true };
}

// Deep (filesystem-touching) provenance verification: every hash reference
// the record claims is resolved against the approved artifacts root and
// content-hashed. `complete` is true only when the raw artifact and every
// one of the ten required hash-reference fields resolve and match — the
// bar `verified` maturity actually requires (see evidence-maturity.js).
function verifyProvenanceChain(record, opts = {}) {
  const artifactsRoot = opts.artifactsRoot ?? defaultArtifactsRoot();
  const reasons = [];

  const raw = verifyRawArtifact(record.provenance.rawArtifact, { artifactsRoot });
  if (!raw.ok) reasons.push(raw.reason);

  let allHashRefsComplete = true;
  for (const field of HASH_REF_FIELDS) {
    const result = verifyHashRefField(record.provenance[field], field, { artifactsRoot });
    if (!result.ok) reasons.push(result.reason);
    if (!result.complete) allHashRefsComplete = false;
  }

  reasons.push(...validateSourceRevision(record.provenance.sourceCommit.revision).map((e) => `provenance.sourceCommit: ${e}`));

  const complete = reasons.length === 0 && raw.checked === true && allHashRefsComplete;
  return { complete, reasons };
}

// Walks a chain of `statistic.derivedFrom.sourceRecordHash` references
// looking for a cycle or a hash that doesn't resolve to any known record
// (missing link). `resolveByHash(hash)` is supplied by the caller (the
// content gate has the full manifest set; a unit test can supply a small
// synthetic map).
function detectDerivationCycle(record, resolveByHash, { maxDepth = 50 } = {}) {
  const visited = new Set([record.provenance.canonicalResultHash]);
  let current = record;
  let depth = 0;
  while (isPlainObject(current.statistic) && current.statistic.derivedFrom !== null) {
    depth += 1;
    if (depth > maxDepth) return { ok: false, reason: "derivation chain exceeds the maximum depth (likely cycle)" };
    const sourceHash = current.statistic.derivedFrom.sourceRecordHash;
    if (visited.has(sourceHash)) return { ok: false, reason: `derivation cycle detected: canonicalResultHash "${sourceHash}" is its own ancestor` };
    visited.add(sourceHash);
    const next = resolveByHash(sourceHash);
    if (!next) return { ok: false, reason: `derivedFrom.sourceRecordHash "${sourceHash}" does not resolve to any known record (missing link)` };
    current = next;
  }
  return { ok: true };
}

// Checks one record's provenance chain is shape-valid (schema.js) — deep
// hash verification is a separate, explicit step (verifyProvenanceChain)
// since it touches the filesystem and a schema-shape check alone is what
// most callers (e.g. a fast pre-commit check) want.
function validateProvenanceChain(record, { cwd, artifactsRoot } = {}) {
  const { valid: schemaValid, errors: schemaErrors } = validateResult(record);
  if (!schemaValid) {
    return { valid: false, errors: schemaErrors };
  }
  const deep = verifyProvenanceChain(record, { artifactsRoot: artifactsRoot ?? (cwd ? resolve(cwd, "scripts/benchmark-platform/results/artifacts") : undefined) });
  return { valid: deep.reasons.length === 0, errors: deep.reasons, complete: deep.complete };
}

// Cross-language comparability for a Java/Rust record pair, reusing
// scripts/benchmark-platform/comparability.js's validateComparability.
function validateRecordPairComparability(javaRecord, rustRecord) {
  const toComparabilityInput = (record) => ({
    buildMode: record.comparability?.buildMode ?? null,
    datasetId: record.comparability?.datasetId ?? null,
    semanticsFixtureHash: record.comparability?.semanticsFixtureHash ?? null,
    warmup: record.comparability?.warmup ?? null,
  });
  return validateComparability(toComparabilityInput(javaRecord), toComparabilityInput(rustRecord));
}

// Best-effort: does `revision` actually resolve in this checkout's git
// history? Never fatal outside a git checkout (a tarball export, CI cache
// miss) — absence of git is reported as "unverifiable", not "invalid".
function resolvesInGitHistory(revision, { cwd = process.cwd() } = {}) {
  if (revision === "unversioned") return { ok: true, verifiable: false };
  try {
    execFileSync("git", ["cat-file", "-e", revision], { cwd, stdio: ["ignore", "ignore", "ignore"] });
    return { ok: true, verifiable: true };
  } catch (err) {
    if (err.code === "ENOENT") return { ok: true, verifiable: false }; // no git binary
    return { ok: false, verifiable: true, reason: `revision "${revision}" was not found in this checkout's git history` };
  }
}

export {
  MAX_ARTIFACT_BYTES,
  defaultArtifactsRoot,
  resolveWithinRoot,
  hashArtifactFile,
  verifyRawArtifact,
  verifyHashRefField,
  verifyProvenanceChain,
  detectDerivationCycle,
  validateProvenanceChain,
  validateRecordPairComparability,
  validateSourceRevision,
  resolvesInGitHistory,
};
