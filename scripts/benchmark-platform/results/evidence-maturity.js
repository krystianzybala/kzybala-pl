// Evidence maturity (plab-003 task 7; rewritten in the 2026-07-14
// remediation pass). The v1 version stored `evidenceMaturity` as a bare
// string a caller could set directly, with a legality table
// (`ALLOWED_TRANSITIONS`) that was never actually consulted by the content
// gate — the audit constructed a schema-valid, provenance-shape-valid
// record with `evidenceMaturity: "verified"` and a fabricated
// rawArtifactPath/sourceRevision/environmentRef and watched it pass both
// validators. There is no stored maturity field anymore: `deriveMaturity`
// recomputes a badge from the record's independent evidence dimensions
// (schema.js's `evidence` object) and its actual, hash-verified provenance
// chain, every time it is called. A record cannot be "verified" by writing
// the word "verified" anywhere in it.
import { verifyProvenanceChain } from "./provenance.js";

const MATURITY_LEVELS = ["draft", "reproduced", "profiled", "verified", "legacy-unprovenanced"];

const BADGE_LABELS = {
  draft: { label: "Draft — informal, not for a benchmark.md table", isPublishable: false },
  reproduced: { label: "Reproduced — re-run independently with a compatible result", isPublishable: true },
  profiled: { label: "Profiled — reproduced, with profiler evidence attached", isPublishable: true },
  verified: { label: "Verified — reviewed and accepted", isPublishable: true },
  "legacy-unprovenanced": { label: "Legacy — pre-plab-003, no raw artifact retained, cannot be a regression baseline or contribute to a verified/Java-vs-Rust conclusion", isPublishable: true },
};

function badgeFor(level) {
  const badge = BADGE_LABELS[level];
  if (!badge) throw new Error(`no badge defined for evidence-maturity level "${level}"`);
  return badge;
}

// `verified` requires ALL of (audit section 6 / remediation requirement 6):
//   - complete, hash-verified provenance (every required chain link resolves and matches),
//   - evidence.correctness === "passed",
//   - evidence.environment === "native-controlled" (rejects container/vm/emulated/unknown/native-uncontrolled),
//   - independent reproduction: reproduction.completed >= reproduction.required >= 1,
//   - evidence.profiling === "present",
//   - evidence.comparability === "validated" (or "not-applicable" for a single-language record with no cross-language claim),
//   - a recorded reviewer identity + timestamp,
//   - evidence.importerCapability === "live-publication-validated" (fixture-only/live-smoke-validated can never satisfy this),
//   - no unresolved evidence.warnings,
//   - evidence.legacy === false.
// legacy-unprovenanced is checked FIRST and is an unconditional, terminal
// short-circuit: nothing else in `evidence` or `provenance` can override it,
// by construction (schema.js additionally rejects a legacy record that
// tries to carry non-baseline evidence/provenance values at all).
function deriveMaturity(record, opts = {}) {
  if (record.evidence.legacy === true) {
    return { level: "legacy-unprovenanced", isPublishable: true, isVerified: false, reasons: [], checks: null };
  }

  const chain = verifyProvenanceChain(record, opts);

  const checks = {
    provenanceComplete: chain.complete,
    correctness: record.evidence.correctness === "passed",
    environment: record.evidence.environment === "native-controlled",
    reproduction: record.evidence.reproduction.required >= 1 && record.evidence.reproduction.completed >= record.evidence.reproduction.required,
    profiling: record.evidence.profiling === "present",
    comparability: record.evidence.comparability === "validated" || record.evidence.comparability === "not-applicable",
    reviewer: record.evidence.reviewer !== null,
    importerCapability: record.evidence.importerCapability === "live-publication-validated",
    noWarnings: record.evidence.warnings.length === 0,
  };

  const unmet = Object.entries(checks)
    .filter(([, ok]) => !ok)
    .map(([name]) => name);
  const verified = unmet.length === 0;

  let level;
  if (verified) {
    level = "verified";
  } else if (checks.provenanceComplete && checks.reproduction && checks.profiling) {
    level = "profiled";
  } else if (checks.provenanceComplete && checks.reproduction) {
    level = "reproduced";
  } else {
    level = "draft";
  }

  const reasons = verified
    ? []
    : [`unmet condition(s) for "verified": ${unmet.join(", ")}`, ...chain.reasons.map((r) => `provenance: ${r}`)];

  return { level, isPublishable: level !== "draft", isVerified: level === "verified", reasons, checks };
}

export { MATURITY_LEVELS, BADGE_LABELS, badgeFor, deriveMaturity };
