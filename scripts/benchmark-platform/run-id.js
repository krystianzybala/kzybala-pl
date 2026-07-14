import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { join } from "node:path";

// Deterministic JSON.stringify: sorts object keys recursively so the same
// logical input always serializes identically regardless of property
// insertion order — required for a hash to be stable across callers/runs.
function stableStringify(value) {
  if (Array.isArray(value)) {
    return `[${value.map(stableStringify).join(",")}]`;
  }
  if (value !== null && typeof value === "object") {
    const keys = Object.keys(value).sort();
    return `{${keys.map((key) => `${JSON.stringify(key)}:${stableStringify(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

// The last commit that touched this lab's code directory — the
// "implementation revision" component of a run id (design.md "Result
// identity": hash the lab id, implementation revision, toolchain,
// parameters and environment metadata). Returns "unversioned" outside a git
// checkout (e.g. a tarball export) rather than fabricating a revision.
function resolveImplementationRevision(labId, labsRoot) {
  const dir = join(labsRoot, labId, "code");
  try {
    return execFileSync("git", ["log", "-1", "--format=%h", "--", dir], {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    }).trim() || "unversioned";
  } catch {
    return "unversioned";
  }
}

// Computes a stable run id: sha256 over {labId, implementationRevision,
// toolchain, profile, params, environment}, truncated to 16 hex chars for
// readability in file paths. Same logical inputs -> same id, always;
// changing any one field changes the id.
function computeRunId({ labId, implementationRevision, toolchain, profile, params, environment }) {
  if (!labId || !implementationRevision || !toolchain || !profile) {
    throw new Error("computeRunId requires labId, implementationRevision, toolchain and profile");
  }
  const canonical = stableStringify({ labId, implementationRevision, toolchain, profile, params: params ?? {}, environment: environment ?? {} });
  return createHash("sha256").update(canonical).digest("hex").slice(0, 16);
}

export { stableStringify, resolveImplementationRevision, computeRunId };
