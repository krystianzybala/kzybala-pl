// Evidence-maturity workflow (plab-003 task 7).
// design.md's "Review workflow": "A run moves draft -> reproduced ->
// profiled -> verified. Site badges reflect actual state and may regress if
// provenance is invalidated." `legacy-unprovenanced` (schema.js) sits
// outside this forward progression entirely — it is not a stage a new run
// passes through, it is a permanent label for pre-plab-003 data
// (docs/benchmark-results-migration.md) that can only ever be *replaced* by
// a real draft run, never promoted in place.
import { EVIDENCE_MATURITY } from "./schema.js";

const FORWARD_ORDER = ["draft", "reproduced", "profiled", "verified"];

// Explicit transition table rather than a simple "is the next index higher"
// check: profiled and reproduced are siblings a run can visit in either
// order before verification (a profiler capture doesn't require a second
// independent re-run first), but nothing may skip past verified without
// going through at least one of them, and anything may regress to "draft"
// if its provenance is invalidated (design.md: "may regress if provenance is
// invalidated").
const ALLOWED_TRANSITIONS = {
  draft: ["reproduced", "profiled"],
  reproduced: ["profiled", "verified", "draft"],
  profiled: ["reproduced", "verified", "draft"],
  verified: ["draft"],
  "legacy-unprovenanced": [],
};

function canTransition(from, to) {
  if (!EVIDENCE_MATURITY.includes(from) || !EVIDENCE_MATURITY.includes(to)) {
    return false;
  }
  return (ALLOWED_TRANSITIONS[from] ?? []).includes(to);
}

// Applies a transition, returning the new maturity. Throws on an
// illegal/unknown transition — a workflow bug that silently accepted an
// invalid state change would be exactly the kind of unearned "verified"
// badge design.md's review workflow exists to prevent.
function transition(current, next) {
  if (!canTransition(current, next)) {
    throw new Error(`illegal evidence-maturity transition: "${current}" -> "${next}"`);
  }
  return next;
}

// Human-facing badge label + whether that badge should render as a
// "verified" (trustworthy for a benchmark.md table) claim.
const BADGE_LABELS = {
  draft: { label: "Draft — informal, not for a benchmark.md table", isPublishable: false },
  reproduced: { label: "Reproduced — re-run once with a compatible result", isPublishable: true },
  profiled: { label: "Profiled — reproduced, with profiler evidence attached", isPublishable: true },
  verified: { label: "Verified — reviewed and accepted", isPublishable: true },
  "legacy-unprovenanced": { label: "Legacy — pre-plab-003, no raw artifact retained", isPublishable: true },
};

function badgeFor(maturity) {
  const badge = BADGE_LABELS[maturity];
  if (!badge) {
    throw new Error(`no badge defined for evidence maturity "${maturity}"`);
  }
  return badge;
}

export { FORWARD_ORDER, ALLOWED_TRANSITIONS, canTransition, transition, badgeFor };
