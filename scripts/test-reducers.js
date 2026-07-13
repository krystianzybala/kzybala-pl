#!/usr/bin/env node
// Unit tests for pure reducers and framework helpers — no DOM required
// (spec.md "Pure transitions": state transitions independent of rendering).
import { test } from "node:test";
import assert from "node:assert/strict";
import { falseSharingInitialState, falseSharingReducer, announceFalseSharing } from "../assets/js/labs/false-sharing-reducer.js";
import { ringBufferInitialState, ringBufferReducer, announceRingBuffer, ringBufferCapacity } from "../assets/js/labs/ring-buffer-reducer.js";
import { jitPipelineInitialState, jitPipelineReducer, announceJitPipeline, jitStageLabels } from "../assets/js/labs/jit-pipeline-reducer.js";
import { parseUrlState, serializeUrlState } from "../assets/js/core/lab-framework.js";
import {
  createFalseSharingState,
  falseSharingScenarios,
  falseSharingReducer as falseSharingLabReducer,
  announceFalseSharing as announceFalseSharingLab,
} from "../assets/js/labs/false-sharing-scenarios.js";
import {
  createCacheHierarchyState,
  cacheHierarchyScenarios,
  cacheHierarchyScenarioSize,
  cacheHierarchyReducer,
  cacheHierarchyEventLabel,
  announceCacheHierarchy,
  MAX_STEPS as CACHE_MAX_STEPS,
} from "../assets/js/labs/cache-hierarchy-scenarios.js";

test("false-sharing: COHERENCE_STEP flips writeSide", () => {
  const s1 = falseSharingReducer(falseSharingInitialState, { type: "COHERENCE_STEP" });
  assert.equal(s1.writeSide, 1);
  const s2 = falseSharingReducer(s1, { type: "COHERENCE_STEP" });
  assert.equal(s2.writeSide, 0);
});

test("false-sharing: RESET returns initial state", () => {
  const stepped = falseSharingReducer(falseSharingInitialState, { type: "COHERENCE_STEP" });
  assert.deepEqual(falseSharingReducer(stepped, { type: "RESET" }), falseSharingInitialState);
});

test("false-sharing: unknown event is a no-op (same reference)", () => {
  assert.equal(falseSharingReducer(falseSharingInitialState, { type: "NOPE" }), falseSharingInitialState);
});

test("false-sharing: announce names both CPUs' states", () => {
  assert.equal(announceFalseSharing({ writeSide: 0 }), "CPU 0 cache line Modified, CPU 1 cache line Invalid.");
});

test("ring-buffer: produce fills to capacity then reports full", () => {
  let state = ringBufferInitialState;
  for (let i = 0; i < ringBufferCapacity; i++) state = ringBufferReducer(state, { type: "PRODUCE" });
  assert.equal(state.size, ringBufferCapacity);
  assert.equal(state.lastResult, "produced");
  const overflow = ringBufferReducer(state, { type: "PRODUCE" });
  assert.equal(overflow.size, ringBufferCapacity);
  assert.equal(overflow.lastResult, "full");
});

test("ring-buffer: consume on empty buffer reports empty, size stays 0", () => {
  const result = ringBufferReducer(ringBufferInitialState, { type: "CONSUME" });
  assert.equal(result.size, 0);
  assert.equal(result.lastResult, "empty");
});

test("ring-buffer: RESET returns initial state", () => {
  const produced = ringBufferReducer(ringBufferInitialState, { type: "PRODUCE" });
  assert.deepEqual(ringBufferReducer(produced, { type: "RESET" }), ringBufferInitialState);
});

test("ring-buffer: announce reflects lastResult", () => {
  assert.match(announceRingBuffer({ size: 1, lastResult: "produced" }), /Produced/);
  assert.match(announceRingBuffer({ lastResult: "full" }), /full/i);
  assert.equal(announceRingBuffer({ lastResult: null }), null);
});

test("jit-pipeline: JIT_STEP cycles through every stage and wraps to start", () => {
  let state = jitPipelineInitialState;
  for (let i = 0; i < jitStageLabels.length; i++) state = jitPipelineReducer(state, { type: "JIT_STEP" });
  assert.deepEqual(state, jitPipelineInitialState);
});

test("jit-pipeline: announce names the current stage", () => {
  assert.equal(announceJitPipeline({ stageIndex: 1 }), `Compilation stage: ${jitStageLabels[1]}.`);
});

test("parseUrlState: reads a valid scenario and step", () => {
  const scenarios = [{ id: "shared-line", label: "Shared line" }];
  assert.deepEqual(parseUrlState("?scenario=shared-line&step=3", scenarios), { scenario: "shared-line", step: 3 });
});

test("parseUrlState: invalid scenario falls back to null rather than applying it", () => {
  const scenarios = [{ id: "shared-line", label: "Shared line" }];
  assert.deepEqual(parseUrlState("?scenario=does-not-exist", scenarios), { scenario: null, step: null });
});

test("parseUrlState: missing or non-numeric step is null", () => {
  assert.deepEqual(parseUrlState("?step=abc", []), { scenario: null, step: null });
  assert.deepEqual(parseUrlState("", []), { scenario: null, step: null });
});

test("serializeUrlState: writes only the scenario/step keys present on state", () => {
  assert.equal(serializeUrlState({ scenario: "shared-line", step: 2 }, ""), "?scenario=shared-line&step=2");
  assert.equal(serializeUrlState({ writeSide: 1 }, ""), null);
});

// --- false-sharing lab (interactive cache-line model) ---

test("false-sharing lab: initial state has both lines shared, no invalidations/transfers", () => {
  const state = createFalseSharingState("shared-line");
  assert.equal(state.cpu0.lineState, "shared");
  assert.equal(state.cpu1.lineState, "shared");
  assert.equal(state.owner, null);
  assert.equal(state.invalidations, 0);
  assert.equal(state.transfers, 0);
  assert.equal(state.step, 0);
});

test("false-sharing lab (shared-line): CPU0_WRITE invalidates CPU1 and claims ownership", () => {
  const state = falseSharingLabReducer(createFalseSharingState("shared-line"), { type: "CPU0_WRITE" });
  assert.equal(state.cpu0.lineState, "modified");
  assert.equal(state.cpu1.lineState, "invalid");
  assert.equal(state.owner, 0);
  assert.equal(state.invalidations, 1);
  assert.equal(state.transfers, 0);
  assert.equal(state.step, 1);
});

test("false-sharing lab (shared-line): a second write from the other CPU transfers ownership", () => {
  let state = createFalseSharingState("shared-line");
  state = falseSharingLabReducer(state, { type: "CPU0_WRITE" });
  state = falseSharingLabReducer(state, { type: "CPU1_WRITE" });
  assert.equal(state.cpu1.lineState, "modified");
  assert.equal(state.cpu0.lineState, "invalid");
  assert.equal(state.owner, 1);
  assert.equal(state.invalidations, 2);
  assert.equal(state.transfers, 1);
});

test("false-sharing lab (shared-line): reading an invalid line fetches a shared copy without a new invalidation", () => {
  let state = createFalseSharingState("shared-line");
  state = falseSharingLabReducer(state, { type: "CPU0_WRITE" });
  state = falseSharingLabReducer(state, { type: "CPU1_READ" });
  assert.equal(state.cpu1.lineState, "shared");
  assert.equal(state.cpu0.lineState, "shared");
  assert.equal(state.owner, null);
  assert.equal(state.invalidations, 1);
  assert.equal(state.transfers, 1);
});

test("false-sharing lab (padded-line): writes on both CPUs never invalidate each other", () => {
  let state = createFalseSharingState("padded-line");
  state = falseSharingLabReducer(state, { type: "CPU0_WRITE" });
  state = falseSharingLabReducer(state, { type: "CPU1_WRITE" });
  state = falseSharingLabReducer(state, { type: "CPU0_WRITE" });
  assert.equal(state.cpu0.lineState, "modified");
  assert.equal(state.cpu1.lineState, "modified");
  assert.equal(state.invalidations, 0);
  assert.equal(state.transfers, 0);
});

test("false-sharing lab: PREVIOUS_STEP exactly restores the prior derived state", () => {
  let state = createFalseSharingState("shared-line");
  state = falseSharingLabReducer(state, { type: "CPU0_WRITE" });
  const afterFirstWrite = state;
  state = falseSharingLabReducer(state, { type: "CPU1_WRITE" });
  state = falseSharingLabReducer(state, { type: "PREVIOUS_STEP" });
  assert.deepEqual(state, { ...afterFirstWrite, history: state.history });
  assert.equal(state.step, 1);
});

test("false-sharing lab: NEXT_STEP redoes after an undo, but a new op truncates the redo branch", () => {
  let state = createFalseSharingState("shared-line");
  state = falseSharingLabReducer(state, { type: "CPU0_WRITE" });
  state = falseSharingLabReducer(state, { type: "CPU1_WRITE" });
  state = falseSharingLabReducer(state, { type: "PREVIOUS_STEP" });
  const redone = falseSharingLabReducer(state, { type: "NEXT_STEP" });
  assert.equal(redone.owner, 1);
  assert.equal(redone.history.length, 2);

  const branched = falseSharingLabReducer(state, { type: "CPU0_READ" });
  assert.equal(branched.history.length, 2);
  assert.equal(branched.history[1], "CPU0_READ");
});

test("false-sharing lab: NEXT_STEP/PREVIOUS_STEP are no-ops at the ends of history (same reference)", () => {
  const initial = createFalseSharingState("shared-line");
  assert.equal(falseSharingLabReducer(initial, { type: "PREVIOUS_STEP" }), initial);
  const stepped = falseSharingLabReducer(initial, { type: "CPU0_WRITE" });
  assert.equal(falseSharingLabReducer(stepped, { type: "NEXT_STEP" }), stepped);
});

test("false-sharing lab: RESET returns the exact initial state for the current scenario", () => {
  let state = createFalseSharingState("padded-line");
  state = falseSharingLabReducer(state, { type: "CPU0_WRITE" });
  state = falseSharingLabReducer(state, { type: "RESET" });
  assert.deepEqual(state, createFalseSharingState("padded-line"));
});

test("false-sharing lab: SELECT_SCENARIO switches scenario and resets history", () => {
  let state = createFalseSharingState("shared-line");
  state = falseSharingLabReducer(state, { type: "CPU0_WRITE" });
  state = falseSharingLabReducer(state, { type: "SELECT_SCENARIO", scenario: "read-mostly" });
  assert.deepEqual(state, createFalseSharingState("read-mostly"));
});

test("false-sharing lab: unknown event is a no-op (same reference)", () => {
  const state = createFalseSharingState("shared-line");
  assert.equal(falseSharingLabReducer(state, { type: "NOPE" }), state);
});

test("false-sharing lab: every scenario id is a valid initial-state scenario", () => {
  for (const scenario of falseSharingScenarios) {
    const state = createFalseSharingState(scenario.id);
    assert.equal(state.scenario, scenario.id);
  }
});

test("false-sharing lab: announce reports the invalidation count after a write", () => {
  const state = falseSharingLabReducer(createFalseSharingState("shared-line"), { type: "CPU0_WRITE" });
  assert.match(announceFalseSharingLab(state, { type: "CPU0_WRITE" }), /Invalidations: 1/);
});

// --- cache-hierarchy lab (interactive working-set model) ---

test("cache-hierarchy lab: initial state is empty with all counts at zero", () => {
  const state = createCacheHierarchyState("sequential-small");
  assert.equal(state.step, 0);
  assert.deepEqual(state.levels, { l1: [], l2: [], l3: [] });
  assert.deepEqual(state.counts, { l1: 0, l2: 0, l3: 0, ram: 0 });
  assert.deepEqual(state.log, []);
});

test("cache-hierarchy lab: every scenario id is a valid initial-state scenario", () => {
  for (const scenario of cacheHierarchyScenarios) {
    const state = createCacheHierarchyState(scenario.id);
    assert.equal(state.scenario, scenario.id);
  }
});

test("cache-hierarchy lab: scenario sizes match the fits-in-L1/exceeds-cache framing", () => {
  assert.equal(cacheHierarchyScenarioSize("sequential-small"), 4);
  assert.equal(cacheHierarchyScenarioSize("random-small"), 4);
  assert.equal(cacheHierarchyScenarioSize("sequential-large"), 32);
  assert.equal(cacheHierarchyScenarioSize("random-large"), 32);
});

test("cache-hierarchy lab (sequential-small): first access is a cold RAM miss that prefetches line 1", () => {
  const state = cacheHierarchyReducer(createCacheHierarchyState("sequential-small"), { type: "NEXT_STEP" });
  assert.deepEqual(state.log[0], { step: 1, line: 0, level: "ram", prefetched: 1 });
  assert.deepEqual(state.counts, { l1: 0, l2: 0, l3: 0, ram: 1 });
});

test("cache-hierarchy lab (sequential-small): settles into all-L1-hit once the 4-line working set has been touched once", () => {
  let state = createCacheHierarchyState("sequential-small");
  for (let i = 0; i < 6; i++) state = cacheHierarchyReducer(state, { type: "NEXT_STEP" });
  // Steps 5 and 6 revisit lines 0 and 1, already resident from steps 1-4.
  assert.equal(state.log[4].level, "l1");
  assert.equal(state.log[5].level, "l1");
  assert.deepEqual(state.counts, { l1: 2, l2: 3, l3: 0, ram: 1 });
});

test("cache-hierarchy lab (sequential-large): hardware prefetch keeps almost every access off RAM despite exceeding total cache capacity", () => {
  let state = createCacheHierarchyState("sequential-large");
  for (let i = 0; i < CACHE_MAX_STEPS; i++) state = cacheHierarchyReducer(state, { type: "NEXT_STEP" });
  // Only the very first access is a cold miss; the prefetcher stays one line
  // ahead of every subsequent demand access for the rest of the run.
  assert.equal(state.counts.ram, 1);
  assert.equal(state.counts.l2, CACHE_MAX_STEPS - 1);
});

test("cache-hierarchy lab (random-large): no stride to prefetch and a working set larger than the visible window means every access misses to RAM", () => {
  let state = createCacheHierarchyState("random-large");
  for (let i = 0; i < CACHE_MAX_STEPS; i++) state = cacheHierarchyReducer(state, { type: "NEXT_STEP" });
  assert.deepEqual(state.counts, { l1: 0, l2: 0, l3: 0, ram: CACHE_MAX_STEPS });
  assert.ok(state.log.every(entry => entry.prefetched === null));
});

test("cache-hierarchy lab (random-small): no prefetch, but still settles into all-L1-hit once every line has been touched once", () => {
  let state = createCacheHierarchyState("random-small");
  for (let i = 0; i < CACHE_MAX_STEPS; i++) state = cacheHierarchyReducer(state, { type: "NEXT_STEP" });
  assert.deepEqual(state.counts, { l1: 20, l2: 0, l3: 0, ram: 4 });
  assert.ok(state.log.every(entry => entry.prefetched === null));
});

test("cache-hierarchy lab: PREVIOUS_STEP exactly restores the prior derived state", () => {
  let state = createCacheHierarchyState("sequential-large");
  state = cacheHierarchyReducer(state, { type: "NEXT_STEP" });
  const afterStep1 = state;
  state = cacheHierarchyReducer(state, { type: "NEXT_STEP" });
  state = cacheHierarchyReducer(state, { type: "PREVIOUS_STEP" });
  assert.deepEqual(state, afterStep1);
});

test("cache-hierarchy lab: NEXT_STEP/PREVIOUS_STEP are no-ops at the ends of the run (same reference)", () => {
  const initial = createCacheHierarchyState("sequential-small");
  assert.equal(cacheHierarchyReducer(initial, { type: "PREVIOUS_STEP" }), initial);
  let atMax = initial;
  for (let i = 0; i < CACHE_MAX_STEPS; i++) atMax = cacheHierarchyReducer(atMax, { type: "NEXT_STEP" });
  assert.equal(cacheHierarchyReducer(atMax, { type: "NEXT_STEP" }), atMax);
});

test("cache-hierarchy lab: RESET returns the exact initial state for the current scenario", () => {
  let state = createCacheHierarchyState("random-large");
  state = cacheHierarchyReducer(state, { type: "NEXT_STEP" });
  state = cacheHierarchyReducer(state, { type: "RESET" });
  assert.deepEqual(state, createCacheHierarchyState("random-large"));
});

test("cache-hierarchy lab: SELECT_SCENARIO switches scenario and resets progress", () => {
  let state = createCacheHierarchyState("sequential-small");
  state = cacheHierarchyReducer(state, { type: "NEXT_STEP" });
  state = cacheHierarchyReducer(state, { type: "SELECT_SCENARIO", scenario: "random-large" });
  assert.deepEqual(state, createCacheHierarchyState("random-large"));
});

test("cache-hierarchy lab: SELECT_SCENARIO with the current scenario is a no-op (same reference)", () => {
  const state = createCacheHierarchyState("sequential-small");
  assert.equal(cacheHierarchyReducer(state, { type: "SELECT_SCENARIO", scenario: "sequential-small" }), state);
});

test("cache-hierarchy lab: unknown event is a no-op (same reference)", () => {
  const state = createCacheHierarchyState("sequential-small");
  assert.equal(cacheHierarchyReducer(state, { type: "NOPE" }), state);
});

test("cache-hierarchy lab: event label names the line and outcome, including prefetch", () => {
  assert.equal(
    cacheHierarchyEventLabel({ step: 1, line: 0, level: "ram", prefetched: 1 }),
    "Step 1: accessed line 0 — RAM miss; prefetched line 1 into L2."
  );
  assert.equal(
    cacheHierarchyEventLabel({ step: 5, line: 0, level: "l1", prefetched: null }),
    "Step 5: accessed line 0 — L1 hit."
  );
});

test("cache-hierarchy lab: announce describes the last step, reset, and scenario change", () => {
  const stepped = cacheHierarchyReducer(createCacheHierarchyState("sequential-small"), { type: "NEXT_STEP" });
  assert.match(announceCacheHierarchy(stepped, { type: "NEXT_STEP" }), /RAM miss/);
  assert.equal(announceCacheHierarchy(stepped, { type: "RESET" }), "Reset. Cache hierarchy empty, no accesses yet.");
  assert.match(announceCacheHierarchy(stepped, { type: "SELECT_SCENARIO" }), /Scenario changed to/);
  assert.equal(announceCacheHierarchy(stepped, { type: "NOPE" }), null);
});
