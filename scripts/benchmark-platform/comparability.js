// Encodes spec.md's "Comparable configuration" requirement: cross-language
// comparison SHALL reject or visibly flag debug builds, unequal datasets,
// unequal semantics, or missing warm-up/configuration metadata.
//
// Each language-side run description is expected to look like:
//   {
//     buildMode: "release" | "debug",
//     datasetId: string,
//     semanticsFixtureHash: string | null,   // hash of the shared fixture it was checked against
//     warmup: { iterations: number, time: string } | null,  // null = not configured/recorded
//   }

const STATUS = { COMPARABLE: "comparable", NON_COMPARABLE: "non-comparable", INVALID: "invalid" };

function validateComparability(java, rust) {
  const reasons = [];
  let status = STATUS.COMPARABLE;

  const escalate = (next) => {
    // invalid outranks non-comparable outranks comparable
    if (next === STATUS.INVALID || status === STATUS.INVALID) {
      status = STATUS.INVALID;
    } else if (next === STATUS.NON_COMPARABLE) {
      status = STATUS.NON_COMPARABLE;
    }
  };

  if (java.buildMode === "debug" || rust.buildMode === "debug") {
    reasons.push(`debug build detected (java=${java.buildMode}, rust=${rust.buildMode})`);
    escalate(STATUS.INVALID);
  }

  if (!java.warmup || !rust.warmup) {
    reasons.push("missing warm-up/configuration metadata on at least one side");
    escalate(STATUS.INVALID);
  }

  if (java.datasetId !== rust.datasetId) {
    reasons.push(`unequal dataset (java="${java.datasetId}", rust="${rust.datasetId}")`);
    escalate(STATUS.NON_COMPARABLE);
  }

  if (!java.semanticsFixtureHash || !rust.semanticsFixtureHash) {
    reasons.push("missing shared-semantics fixture hash on at least one side — equivalence unconfirmed");
    escalate(STATUS.NON_COMPARABLE);
  } else if (java.semanticsFixtureHash !== rust.semanticsFixtureHash) {
    reasons.push(`unequal semantics fixture (java="${java.semanticsFixtureHash}", rust="${rust.semanticsFixtureHash}")`);
    escalate(STATUS.NON_COMPARABLE);
  }

  return { status, reasons };
}

// A profile claiming "publication" rigor (requiresControlledHost, per
// content/labs/_shared/benchmark-profiles.json) is only valid when the
// environment carries an explicit controlled-host marker — never inferred
// from the profile name alone.
function validateProfileClaim(profileName, profile, environment) {
  if (!profile.requiresControlledHost) {
    return { status: STATUS.COMPARABLE, reasons: [] };
  }
  if (environment?.controlledHost !== true) {
    return {
      status: STATUS.INVALID,
      reasons: [`profile "${profileName}" requires a controlled host (docs/benchmark-publication-procedure.md) but environment.controlledHost was not set`],
    };
  }
  return { status: STATUS.COMPARABLE, reasons: [] };
}

export { STATUS, validateComparability, validateProfileClaim };
