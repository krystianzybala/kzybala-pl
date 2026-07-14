// Canonical hashing primitives shared by schema.js, provenance.js and every
// importer (kept in its own module so schema.js <-> provenance.js do not
// need a circular import to share a hash function).
//
// Serialization contract for anything hashed here (audit section 3):
//   - UTF-8 encoding: Node's createHash().update(string) encodes UTF-8 by
//     default; no other encoding is ever used.
//   - Stable key ordering: stableStringify sorts object keys recursively.
//   - Stable array ordering: arrays are hashed in the order given — callers
//     that don't consider order semantically meaningful (e.g. a set of
//     records) must sort before calling, exactly like render.js already
//     does for chart/table output.
//   - Stable newline policy: the canonical string is single-line, compact
//     JSON (no pretty-printing), so no newline choice is ever hash-relevant.
//   - Canonical numeric-string normalization: numeric leaves are the
//     `numeric.js` { value, numericType } shape, i.e. plain strings once
//     serialized — normalizeNumericString (numeric.js) is the single place
//     that decides "-0" vs "0", trailing zeros, etc., so the same logical
//     value always reaches this hasher as the same string.
//   - Unicode normalization: every string field accepted into a canonical
//     record (labId, variant, notes, ...) is passed through
//     `normalizeUnicode` before it is stored, so two byte-different but
//     canonically-equivalent strings (e.g. "µs" typed as a precomposed vs.
//     decomposed codepoint sequence) hash identically.
import { createHash } from "node:crypto";

function normalizeUnicode(value) {
  return typeof value === "string" ? value.normalize("NFC") : value;
}

function stableStringify(value) {
  if (Array.isArray(value)) {
    return `[${value.map(stableStringify).join(",")}]`;
  }
  if (value !== null && typeof value === "object") {
    const keys = Object.keys(value).sort();
    return `{${keys.map((key) => `${JSON.stringify(normalizeUnicode(key))}:${stableStringify(value[key])}`).join(",")}}`;
  }
  if (typeof value === "string") {
    return JSON.stringify(normalizeUnicode(value));
  }
  return JSON.stringify(value);
}

const HEX64_PATTERN = /^[0-9a-f]{64}$/;

function sha256Hex(input) {
  return createHash("sha256").update(input, "utf8").digest("hex");
}

function isHex64(value) {
  return typeof value === "string" && HEX64_PATTERN.test(value);
}

// Hashes the *measured statistic itself* (audit finding C1: "the only
// sha256 in the codebase excludes `statistic` entirely — a published number
// could be edited post-hoc with nothing to detect it"). Anything that
// changes the reported value, its error, its estimate kind, or which
// estimate is primary changes this hash.
function computeCanonicalResultHash(statistic) {
  return sha256Hex(stableStringify(statistic));
}

// Hashes real file content — used for raw artifacts, environment/toolchain
// manifests, correctness-gate output, profiling artifacts: anything that is
// an actual file on disk under an approved root (see provenance.js).
function sha256File(buffer) {
  return createHash("sha256").update(buffer).digest("hex");
}

export { stableStringify, normalizeUnicode, sha256Hex, sha256File, isHex64, computeCanonicalResultHash, HEX64_PATTERN };
