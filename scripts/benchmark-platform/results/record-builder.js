// Shared provenance/evidence object construction for every importer
// (plab-003 remediation). Keeping this in one place means the ten-field
// provenance shape and the nine-field evidence shape are defined exactly
// once — an importer cannot "forget" a field the way the v1 per-importer
// object literals silently could (e.g. v1's JMH importer never had a
// `secondaryMetrics` slot at all).
import { computeCanonicalResultHash } from "./canonical-hash.js";

function hashRef(value) {
  return { ref: value?.ref ?? null, hash: value?.hash ?? null };
}

// `meta.rawArtifact`/`meta.<hashRefField>` are expected to already be
// `{ path/ref, sha256/hash }` — an importer normalizes shape, it does not
// hash a file on the caller's behalf (that would let an importer silently
// "bless" whatever bytes happen to be on disk at import time rather than
// the caller making an explicit, reviewable claim about what artifact this
// is).
function buildProvenance(statistic, meta, importerVersion) {
  return {
    rawArtifact: meta.rawArtifact ?? null,
    canonicalResultHash: computeCanonicalResultHash(statistic),
    sourceCommit: {
      revision: meta.sourceRevision ?? "unversioned",
      dirty: meta.dirty ?? null,
      diffHash: meta.diffHash ?? null,
    },
    dataset: hashRef(meta.dataset),
    semanticFixture: hashRef(meta.semanticFixture),
    environmentManifest: hashRef(meta.environmentManifest),
    toolchainManifest: hashRef(meta.toolchainManifest),
    benchmarkProfile: hashRef(meta.benchmarkProfile),
    correctnessGate: hashRef(meta.correctnessGate),
    profilingArtifact: hashRef(meta.profilingArtifact),
    renderedArtifact: hashRef(meta.renderedArtifact),
    command: meta.command ?? null,
    capturedAt: meta.capturedAt ?? null,
    importerVersion,
  };
}

// Every freshly-imported record starts at the honest evidentiary baseline:
// nothing claimed beyond what the caller explicitly supplies. `evidence` is
// never defaulted toward anything publishable — an importer that received
// no `meta.evidence` produces a `draft`-level record, not a "trust me"
// record (see evidence-maturity.js's deriveMaturity, which recomputes the
// badge from these fields rather than trusting a stored label).
function buildEvidence(meta, importerCapability) {
  return {
    legacy: false,
    correctness: meta.evidence?.correctness ?? "not-run",
    environment: meta.evidence?.environment ?? "unknown",
    reproduction: meta.evidence?.reproduction ?? { required: 0, completed: 0 },
    profiling: meta.evidence?.profiling ?? "absent",
    comparability: meta.evidence?.comparability ?? "not-applicable",
    reviewer: meta.evidence?.reviewer ?? null,
    importerCapability,
    warnings: meta.evidence?.warnings ?? [],
  };
}

// The permanent, locked-down baseline for a pre-plab-003 transcribed
// record — every hash reference is null (there is no raw artifact; that is
// the entire point of "legacy-unprovenanced"), and every evidence dimension
// sits at its untouched default. schema.js additionally rejects any
// attempt to set these to anything else on a `legacy: true` record.
function buildLegacyProvenance(statistic, importerVersion) {
  return {
    rawArtifact: null,
    canonicalResultHash: computeCanonicalResultHash(statistic),
    sourceCommit: { revision: "unversioned", dirty: null, diffHash: null },
    dataset: { ref: null, hash: null },
    semanticFixture: { ref: null, hash: null },
    environmentManifest: { ref: null, hash: null },
    toolchainManifest: { ref: null, hash: null },
    benchmarkProfile: { ref: null, hash: null },
    correctnessGate: { ref: null, hash: null },
    profilingArtifact: { ref: null, hash: null },
    renderedArtifact: { ref: null, hash: null },
    command: null,
    capturedAt: null,
    importerVersion,
  };
}

function buildLegacyEvidence() {
  return {
    legacy: true,
    correctness: "not-run",
    environment: "unknown",
    reproduction: { required: 0, completed: 0 },
    profiling: "absent",
    comparability: "not-applicable",
    reviewer: null,
    importerCapability: "fixture-only",
    warnings: [],
  };
}

export { buildProvenance, buildEvidence, buildLegacyProvenance, buildLegacyEvidence };
