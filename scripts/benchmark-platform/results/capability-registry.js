// Enforced importer capability levels (plab-003 remediation, audit finding:
// "no code-level gate ties evidence-maturity progression to whether an
// importer's data source was a live capture vs. a synthetic fixture").
//
// A capability level is a property of the *importer module*, declared here
// once and read by every importer and by the content gate — not a
// per-record claim a caller can freely assign. Bumping a level is a code
// review event (a maintainer edits this file after real hardware/tool
// validation and documents it — see docs/benchmark-results-importers.md),
// not something a content author can do by hand-editing a JSON record.
//
// Honesty baseline as of this remediation: every importer in this
// repository has only ever been exercised against a synthetic fixture file
// (scripts/benchmark-platform/results/__fixtures__/) — none has ingested
// genuine output captured from a live run of the real tool (JMH/Cargo
// Criterion/perf) on real hardware in this repository. Do not raise any of
// these without documenting the actual validation that justifies it; see
// docs/benchmark-results-importers.md "Capability levels".
const CAPABILITY_LEVELS = ["fixture-only", "live-smoke-validated", "live-publication-validated"];

const CAPABILITY_RANK = Object.fromEntries(CAPABILITY_LEVELS.map((level, index) => [level, index]));

const IMPORTER_CAPABILITIES = {
  "jmh-importer": "fixture-only",
  "criterion-importer": "fixture-only",
  "histogram-importer": "fixture-only",
  "perf-counter-importer": "fixture-only",
  "legacy-migration": "fixture-only",
};

// Parses the `provenance.importerVersion` convention ("<name>@<version>")
// used by every importer in this pipeline.
function importerNameFromVersion(importerVersion) {
  if (typeof importerVersion !== "string") return null;
  const at = importerVersion.lastIndexOf("@");
  return at === -1 ? importerVersion : importerVersion.slice(0, at);
}

function capabilityCeilingFor(importerVersion) {
  const name = importerNameFromVersion(importerVersion);
  return IMPORTER_CAPABILITIES[name] ?? null;
}

// True when `claimed` is no more permissive than what the importer that
// produced this record is actually registered for — this is the check that
// stops a hand-edited record from claiming "live-publication-validated"
// while its importerVersion is still registered "fixture-only".
function isCapabilityClaimAllowed(importerVersion, claimed) {
  const ceiling = capabilityCeilingFor(importerVersion);
  if (ceiling === null || !CAPABILITY_LEVELS.includes(claimed)) return false;
  return CAPABILITY_RANK[claimed] <= CAPABILITY_RANK[ceiling];
}

export { CAPABILITY_LEVELS, IMPORTER_CAPABILITIES, importerNameFromVersion, capabilityCeilingFor, isCapabilityClaimAllowed };
