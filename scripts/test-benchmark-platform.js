#!/usr/bin/env node
// Unit tests for the reproducible-benchmark platform tooling (plab-002):
// profile resolution, run-id determinism/stability, comparability rules,
// and environment metadata shape. No JVM/Cargo/network required — these
// exercise the pure JS logic; scripts/benchmark-platform/*.md docs record
// the real mvn/cargo runs used to validate the tooling end to end.
import { test } from "node:test";
import assert from "node:assert/strict";
import { resolveProfile, jmhArgs, criterionArgs, PROFILE_NAMES } from "./benchmark-platform/profiles.js";
import { stableStringify, computeRunId } from "./benchmark-platform/run-id.js";
import { validateComparability, validateProfileClaim, STATUS } from "./benchmark-platform/comparability.js";
import { captureEnvironment } from "./benchmark-platform/environment.js";

// --- profiles ---

test("resolveProfile: all four required profiles exist and resolve", () => {
  for (const name of PROFILE_NAMES) {
    const profile = resolveProfile(name);
    assert.ok(profile.purpose, `profile "${name}" has no purpose`);
    assert.ok(profile.jmh, `profile "${name}" has no jmh config`);
    assert.ok(profile.criterion, `profile "${name}" has no criterion config`);
  }
});

test("resolveProfile: unknown name throws with the known-profile list", () => {
  assert.throws(() => resolveProfile("nonexistent"), /unknown benchmark profile "nonexistent"/);
});

test("jmhArgs: renders JMH's own CLI flags in order", () => {
  const profile = resolveProfile("smoke");
  assert.deepEqual(jmhArgs(profile), ["-f", "1", "-wi", "0", "-w", "200ms", "-i", "1", "-r", "200ms"]);
});

test("criterionArgs: renders the profile's Criterion CLI flags verbatim", () => {
  const profile = resolveProfile("smoke");
  assert.deepEqual(criterionArgs(profile), ["--quick", "--noplot"]);
});

test("publication profile requires a controlled host; smoke does not", () => {
  assert.equal(resolveProfile("publication").requiresControlledHost, true);
  assert.equal(resolveProfile("smoke").requiresControlledHost, undefined);
});

// --- run-id ---

test("stableStringify: key order does not change the serialization", () => {
  assert.equal(stableStringify({ a: 1, b: 2 }), stableStringify({ b: 2, a: 1 }));
});

test("computeRunId: identical logical input (regardless of key order) is stable", () => {
  const base = { labId: "false-sharing", implementationRevision: "abc123", toolchain: { java: "26.0.1", rustc: "1.88.0" }, profile: "smoke", params: { a: 1, b: 2 } };
  const reordered = { ...base, toolchain: { rustc: "1.88.0", java: "26.0.1" }, params: { b: 2, a: 1 } };
  assert.equal(computeRunId(base), computeRunId(reordered));
});

test("computeRunId: changing any single field changes the id", () => {
  const base = { labId: "false-sharing", implementationRevision: "abc123", toolchain: { java: "26.0.1" }, profile: "smoke", params: {} };
  const id = computeRunId(base);
  assert.notEqual(computeRunId({ ...base, profile: "full" }), id);
  assert.notEqual(computeRunId({ ...base, labId: "cas-contention" }), id);
  assert.notEqual(computeRunId({ ...base, implementationRevision: "def456" }), id);
});

test("computeRunId: requires labId, implementationRevision, toolchain and profile", () => {
  assert.throws(() => computeRunId({ labId: "x" }), /requires labId/);
});

test("computeRunId: is a 16-character lowercase hex string", () => {
  const id = computeRunId({ labId: "x", implementationRevision: "r", toolchain: {}, profile: "smoke" });
  assert.match(id, /^[0-9a-f]{16}$/);
});

// --- comparability ---

const COMPARABLE_RUN = { buildMode: "release", datasetId: "d1", semanticsFixtureHash: "abc", warmup: { iterations: 3, time: "1s" } };

test("validateComparability: matching release runs with shared fixture are comparable", () => {
  const result = validateComparability(COMPARABLE_RUN, COMPARABLE_RUN);
  assert.deepEqual(result, { status: STATUS.COMPARABLE, reasons: [] });
});

test("validateComparability: a debug build on either side is invalid", () => {
  const result = validateComparability(COMPARABLE_RUN, { ...COMPARABLE_RUN, buildMode: "debug" });
  assert.equal(result.status, STATUS.INVALID);
  assert.match(result.reasons[0], /debug build detected/);
});

test("validateComparability: missing warm-up config on either side is invalid", () => {
  const result = validateComparability(COMPARABLE_RUN, { ...COMPARABLE_RUN, warmup: null });
  assert.equal(result.status, STATUS.INVALID);
});

test("validateComparability: unequal dataset is non-comparable, not invalid", () => {
  const result = validateComparability(COMPARABLE_RUN, { ...COMPARABLE_RUN, datasetId: "d2" });
  assert.equal(result.status, STATUS.NON_COMPARABLE);
});

test("validateComparability: unequal or missing semantics fixture hash is non-comparable", () => {
  assert.equal(validateComparability(COMPARABLE_RUN, { ...COMPARABLE_RUN, semanticsFixtureHash: "xyz" }).status, STATUS.NON_COMPARABLE);
  assert.equal(validateComparability(COMPARABLE_RUN, { ...COMPARABLE_RUN, semanticsFixtureHash: null }).status, STATUS.NON_COMPARABLE);
});

test("validateComparability: invalid outranks non-comparable when both apply", () => {
  const result = validateComparability(COMPARABLE_RUN, { ...COMPARABLE_RUN, buildMode: "debug", datasetId: "d2" });
  assert.equal(result.status, STATUS.INVALID);
  assert.equal(result.reasons.length, 2);
});

test("validateProfileClaim: publication profile without controlledHost marker is invalid", () => {
  const result = validateProfileClaim("publication", { requiresControlledHost: true }, {});
  assert.equal(result.status, STATUS.INVALID);
});

test("validateProfileClaim: publication profile with controlledHost marker is comparable", () => {
  const result = validateProfileClaim("publication", { requiresControlledHost: true }, { controlledHost: true });
  assert.equal(result.status, STATUS.COMPARABLE);
});

test("validateProfileClaim: a profile with no controlled-host requirement always passes", () => {
  assert.equal(validateProfileClaim("smoke", {}, {}).status, STATUS.COMPARABLE);
});

// --- environment ---

test("captureEnvironment: returns the required host-metadata fields, real or explicitly unavailable", () => {
  const env = captureEnvironment();
  assert.ok(env.arch);
  assert.ok(env.platform);
  assert.ok(env.cpu.logicalCores > 0);
  assert.ok(env.memory.totalBytes > 0);
  // Never silently omitted — always present as a value or a {status, reason}.
  assert.ok(env.coreTopology.kind || env.coreTopology.status);
  assert.ok(env.powerManagement.status);
});
