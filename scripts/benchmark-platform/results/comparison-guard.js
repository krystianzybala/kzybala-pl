// Mixed-host comparison guard (docs/measurement-environments.md).
//
// A direct comparison between two canonical records — Java-versus-Rust, a
// regression delta, a ranking claim — is only meaningful when both inputs
// come from the same environment class and a compatible, hash-identified
// environment manifest. This module is the single place that decides it,
// derived from the records' evidence/provenance fields — a
// presentation-layer badge can never override it.
//
// Rejected by construction (the directive's canonical examples):
//   - Java on a developer workstation vs Rust on the benchmark host,
//   - an old macOS result vs a new Linux result,
//   - a JDK 26 developer run vs a JDK 21 publication run (different
//     environment manifests),
//   - any legacy-unprovenanced record vs anything.
//
// Development-workstation results may still be *displayed* in a separate
// historical/exploratory section — this guard is about canonical
// comparison tables, regression baselines and cross-language conclusions.

// The environment class is derived, never stored: legacy transcriptions
// and unknown environments are "developer-workstation" by definition of
// how this repository produced them; only a captured, controlled native
// environment counts as the benchmark host.
function environmentClassOf(record) {
  if (record.evidence.legacy === true) return "developer-workstation";
  switch (record.evidence.environment) {
    case "native-controlled":
      return "native-linux-host";
    case "native-uncontrolled":
    case "unknown":
      return "developer-workstation";
    case "container":
    case "vm":
    case "emulated":
      return "virtualized";
    default:
      return "unknown";
  }
}

// canCompare(a, b) -> { allowed, reasons }
// Every reason is reported, not just the first.
function canCompare(a, b) {
  const reasons = [];

  for (const [name, record] of [["first", a], ["second", b]]) {
    if (record.evidence.legacy === true) {
      reasons.push(`${name} record is legacy-unprovenanced — legacy records never enter a canonical comparison`);
    }
  }

  const classA = environmentClassOf(a);
  const classB = environmentClassOf(b);
  if (classA !== classB) {
    reasons.push(`environment classes differ (${classA} vs ${classB}) — mixed-host comparisons are rejected`);
  }
  for (const [name, cls] of [["first", classA], ["second", classB]]) {
    if (cls !== "native-linux-host") {
      reasons.push(`${name} record's environment class "${cls}" is not the dedicated native-Linux benchmark host — canonical comparisons require controlled-host evidence`);
    }
  }

  const manifestA = a.provenance.environmentManifest;
  const manifestB = b.provenance.environmentManifest;
  if (!manifestA?.hash || !manifestB?.hash) {
    reasons.push("both records must carry a hash-identified environment manifest");
  } else if (manifestA.hash !== manifestB.hash) {
    reasons.push(`environment manifests differ (${manifestA.hash.slice(0, 12)}… vs ${manifestB.hash.slice(0, 12)}…) — records from different captured environments (host, kernel, toolchain state) are not directly comparable`);
  }

  return { allowed: reasons.length === 0, reasons };
}

export { canCompare, environmentClassOf };
