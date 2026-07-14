#!/usr/bin/env node
// Lays out one immutable results/<lab-id>/<run-id>/ directory: meta.json +
// raw/. Not wired into any automation yet (docs/benchmark-artifact-layout.md)
// — a maintainer runs this by hand after a real `full`/`publication` run and
// copies the harness's raw output files into the printed `raw/` directory.
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { captureEnvironment } from "./environment.js";
import { computeRunId, resolveImplementationRevision } from "./run-id.js";
import { resolveProfile } from "./profiles.js";

const [labId, profileName] = process.argv.slice(2);
if (!labId || !profileName) {
  console.error("usage: node scripts/benchmark-platform/write-run-artifact.js <lab-id> <profile-name>");
  process.exit(2);
}

const labsRoot = join(import.meta.dirname, "..", "..", "content", "labs");
const environment = captureEnvironment();
const implementationRevision = resolveImplementationRevision(labId, labsRoot);
const profile = resolveProfile(profileName);

const runId = computeRunId({
  labId,
  implementationRevision,
  toolchain: environment.toolchains,
  profile: profileName,
  params: profile,
  environment,
});

const runDir = join(import.meta.dirname, "..", "..", "results", labId, runId);
mkdirSync(join(runDir, "raw"), { recursive: true });
writeFileSync(
  join(runDir, "meta.json"),
  JSON.stringify({ labId, runId, implementationRevision, profile: profileName, profileConfig: profile, environment }, null, 2) + "\n",
);

console.log(runDir);
