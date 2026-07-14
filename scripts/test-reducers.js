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
import {
  createMesiState,
  mesiScenarios,
  mesiReducer,
  mesiEventLabel,
  announceMesi,
} from "../assets/js/labs/mesi-scenarios.js";
import {
  createMemoryOrderingState,
  memoryOrderingScenarios,
  memoryOrderingScenarioMaxSteps,
  memoryOrderingReducer,
  memoryOrderingEventLabel,
  memoryOrderingOutcome,
  announceMemoryOrdering,
} from "../assets/js/labs/memory-ordering-scenarios.js";
import {
  createCasState,
  casScenarios,
  casScenarioMaxSteps,
  casReducer,
  casEventLabel,
  announceCas,
} from "../assets/js/labs/cas-contention-scenarios.js";
import {
  createSpscRingBufferState,
  spscRingBufferScenarios,
  spscRingBufferScenarioMaxSteps,
  spscRingBufferReducer,
  spscRingBufferEventLabel,
  announceSpscRingBuffer,
} from "../assets/js/labs/spsc-ring-buffer-scenarios.js";
import {
  createThreadPerCoreState,
  threadPerCoreScenarios,
  threadPerCoreScenarioMaxSteps,
  threadPerCoreReducer,
  threadPerCoreEventLabel,
  announceThreadPerCore,
} from "../assets/js/labs/thread-per-core-scenarios.js";

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

// --- mesi lab (interactive cache-coherence model) ---

test("mesi lab: initial state has both lines Invalid, no memory value, no counters", () => {
  const state = createMesiState("single-reader");
  assert.equal(state.cpu0.state, "Invalid");
  assert.equal(state.cpu1.state, "Invalid");
  assert.equal(state.memoryValue, 0);
  assert.equal(state.owner, null);
  assert.equal(state.invalidations, 0);
  assert.equal(state.transfers, 0);
  assert.equal(state.writeBacks, 0);
  assert.equal(state.step, 0);
});

test("mesi lab: every scenario id is a valid initial-state scenario", () => {
  for (const scenario of mesiScenarios) {
    const state = createMesiState(scenario.id);
    assert.equal(state.scenario, scenario.id);
  }
});

test("mesi lab (single reader): a read with no sharers fetches from memory and takes Exclusive", () => {
  const state = mesiReducer(createMesiState("single-reader"), { type: "CPU0_READ" });
  assert.equal(state.cpu0.state, "Exclusive");
  assert.equal(state.cpu0.value, 0);
  assert.equal(state.cpu1.state, "Invalid");
  assert.equal(state.owner, 0);
  assert.equal(state.invalidations, 0);
  assert.equal(state.transfers, 0);
  assert.equal(state.writeBacks, 0);
});

test("mesi lab: a read hit on an already-valid line leaves the state unchanged", () => {
  let state = mesiReducer(createMesiState("single-reader"), { type: "CPU0_READ" });
  const afterFirstRead = state;
  state = mesiReducer(state, { type: "CPU0_READ" });
  assert.equal(state.cpu0.state, "Exclusive");
  assert.equal(state.cpu0.value, afterFirstRead.cpu0.value);
  assert.equal(state.transfers, afterFirstRead.transfers);
});

test("mesi lab (two readers): a second reader forces a cache-to-cache transfer and downgrades both to Shared", () => {
  let state = createMesiState("two-readers");
  state = mesiReducer(state, { type: "CPU0_READ" });
  state = mesiReducer(state, { type: "CPU1_READ" });
  assert.equal(state.cpu0.state, "Shared");
  assert.equal(state.cpu1.state, "Shared");
  assert.equal(state.cpu0.value, state.cpu1.value);
  assert.equal(state.owner, null);
  assert.equal(state.transfers, 1);
  assert.equal(state.invalidations, 0);
  assert.equal(state.writeBacks, 0);
});

test("mesi lab (reader then writer): a write from the other CPU invalidates the reader and claims Modified", () => {
  let state = createMesiState("reader-then-writer");
  state = mesiReducer(state, { type: "CPU0_READ" });
  state = mesiReducer(state, { type: "CPU1_WRITE" });
  assert.equal(state.cpu1.state, "Modified");
  assert.equal(state.cpu1.value, 1);
  assert.equal(state.cpu0.state, "Invalid");
  assert.equal(state.owner, 1);
  assert.equal(state.invalidations, 1);
  assert.equal(state.transfers, 1);
  assert.equal(state.writeBacks, 0);
});

test("mesi lab: a write hit on Exclusive silently upgrades to Modified with no bus traffic", () => {
  let state = createMesiState("single-reader");
  state = mesiReducer(state, { type: "CPU0_READ" });
  state = mesiReducer(state, { type: "CPU0_WRITE" });
  assert.equal(state.cpu0.state, "Modified");
  assert.equal(state.cpu0.value, 1);
  assert.equal(state.invalidations, 0);
  assert.equal(state.transfers, 0);
  assert.equal(state.writeBacks, 0);
});

test("mesi lab (competing writers): a write against a Modified holder forces write-back, transfer, and invalidation together", () => {
  let state = createMesiState("competing-writers");
  state = mesiReducer(state, { type: "CPU0_WRITE" });
  state = mesiReducer(state, { type: "CPU1_WRITE" });
  assert.equal(state.cpu0.state, "Invalid");
  assert.equal(state.cpu1.state, "Modified");
  assert.equal(state.cpu1.value, 2);
  assert.equal(state.memoryValue, 1);
  assert.equal(state.owner, 1);
  assert.equal(state.invalidations, 1);
  assert.equal(state.transfers, 1);
  assert.equal(state.writeBacks, 1);
});

test("mesi lab (eviction and write-back): evicting a Modified line writes it back to memory before going Invalid", () => {
  let state = createMesiState("eviction-writeback");
  state = mesiReducer(state, { type: "CPU0_WRITE" });
  state = mesiReducer(state, { type: "CPU0_EVICT" });
  assert.equal(state.cpu0.state, "Invalid");
  assert.equal(state.cpu0.value, null);
  assert.equal(state.memoryValue, 1);
  assert.equal(state.writeBacks, 1);
});

test("mesi lab: evicting a clean (Exclusive) line does not write back", () => {
  let state = createMesiState("eviction-writeback");
  state = mesiReducer(state, { type: "CPU0_READ" });
  state = mesiReducer(state, { type: "CPU0_EVICT" });
  assert.equal(state.cpu0.state, "Invalid");
  assert.equal(state.memoryValue, 0);
  assert.equal(state.writeBacks, 0);
});

test("mesi lab: PREVIOUS_STEP exactly restores the prior derived state", () => {
  let state = createMesiState("competing-writers");
  state = mesiReducer(state, { type: "CPU0_WRITE" });
  const afterFirstWrite = state;
  state = mesiReducer(state, { type: "CPU1_WRITE" });
  state = mesiReducer(state, { type: "PREVIOUS_STEP" });
  assert.deepEqual(state, { ...afterFirstWrite, history: state.history });
  assert.equal(state.step, 1);
});

test("mesi lab: NEXT_STEP redoes after an undo, but a new op truncates the redo branch", () => {
  let state = createMesiState("competing-writers");
  state = mesiReducer(state, { type: "CPU0_WRITE" });
  state = mesiReducer(state, { type: "CPU1_WRITE" });
  state = mesiReducer(state, { type: "PREVIOUS_STEP" });
  const redone = mesiReducer(state, { type: "NEXT_STEP" });
  assert.equal(redone.owner, 1);
  assert.equal(redone.history.length, 2);

  const branched = mesiReducer(state, { type: "CPU0_EVICT" });
  assert.equal(branched.history.length, 2);
  assert.equal(branched.history[1], "CPU0_EVICT");
});

test("mesi lab: NEXT_STEP/PREVIOUS_STEP are no-ops at the ends of history (same reference)", () => {
  const initial = createMesiState("single-reader");
  assert.equal(mesiReducer(initial, { type: "PREVIOUS_STEP" }), initial);
  const stepped = mesiReducer(initial, { type: "CPU0_READ" });
  assert.equal(mesiReducer(stepped, { type: "NEXT_STEP" }), stepped);
});

test("mesi lab: RESET returns the exact initial state for the current scenario", () => {
  let state = createMesiState("competing-writers");
  state = mesiReducer(state, { type: "CPU0_WRITE" });
  state = mesiReducer(state, { type: "RESET" });
  assert.deepEqual(state, createMesiState("competing-writers"));
});

test("mesi lab: SELECT_SCENARIO switches scenario and resets history", () => {
  let state = createMesiState("single-reader");
  state = mesiReducer(state, { type: "CPU0_READ" });
  state = mesiReducer(state, { type: "SELECT_SCENARIO", scenario: "two-readers" });
  assert.deepEqual(state, createMesiState("two-readers"));
});

test("mesi lab: SELECT_SCENARIO with the current scenario is a no-op (same reference)", () => {
  const state = createMesiState("single-reader");
  assert.equal(mesiReducer(state, { type: "SELECT_SCENARIO", scenario: "single-reader" }), state);
});

test("mesi lab: unknown event is a no-op (same reference)", () => {
  const state = createMesiState("single-reader");
  assert.equal(mesiReducer(state, { type: "NOPE" }), state);
});

test("mesi lab: event label names the CPU and the operation", () => {
  assert.equal(mesiEventLabel("CPU0_READ"), "CPU 0 read.");
  assert.equal(mesiEventLabel("CPU1_WRITE"), "CPU 1 wrote.");
  assert.equal(mesiEventLabel("CPU0_EVICT"), "CPU 0 evicted.");
});

test("mesi lab: announce describes CPU states and counters after an op, and reset/scenario-change/unknown", () => {
  const state = mesiReducer(createMesiState("competing-writers"), { type: "CPU0_WRITE" });
  assert.match(announceMesi(state, { type: "CPU0_WRITE" }), /CPU 0: Modified \(value 1\)/);
  assert.equal(announceMesi(state, { type: "RESET" }), "Reset. Both lines Invalid, no invalidations, transfers, or write-backs.");
  assert.match(announceMesi(state, { type: "SELECT_SCENARIO" }), /Scenario changed to/);
  assert.equal(announceMesi(state, { type: "NOPE" }), null);
});

// --- memory-ordering lab (interactive store-buffer/happens-before model) ---

function runToEnd(scenario, ordering) {
  let state = createMemoryOrderingState(scenario, ordering);
  const max = memoryOrderingScenarioMaxSteps(scenario, ordering);
  for (let i = 0; i < max; i++) state = memoryOrderingReducer(state, { type: "NEXT_STEP" });
  return state;
}

test("memory-ordering lab: initial state is empty with no observations, edges, or memory", () => {
  const state = createMemoryOrderingState("broken-publication");
  assert.deepEqual(state.thread0, { pc: 0, buffer: [] });
  assert.deepEqual(state.thread1, { pc: 0, buffer: [] });
  assert.deepEqual(state.memory, {});
  assert.deepEqual(state.observations, []);
  assert.deepEqual(state.edges, []);
  assert.equal(state.step, 0);
});

test("memory-ordering lab: every scenario id is a valid initial-state scenario", () => {
  for (const scenario of memoryOrderingScenarios) {
    const state = createMemoryOrderingState(scenario.id);
    assert.equal(state.scenario, scenario.id);
  }
});

test("memory-ordering lab (broken-publication): plain access lets T1 observe flag=1 but data=0", () => {
  const state = runToEnd("broken-publication", "relaxed");
  const flag = state.observations.find(o => o.thread === 1 && o.var === "flag").value;
  const data = state.observations.find(o => o.thread === 1 && o.var === "data").value;
  assert.equal(flag, 1);
  assert.equal(data, 0);
  assert.deepEqual(state.edges, []);
  assert.match(memoryOrderingOutcome(state), /broken publication/);
});

test("memory-ordering lab (release-acquire): T1 observes flag=1 and therefore data=1, with a happens-before edge", () => {
  const state = runToEnd("release-acquire", "relaxed");
  const flag = state.observations.find(o => o.thread === 1 && o.var === "flag").value;
  const data = state.observations.find(o => o.thread === 1 && o.var === "data").value;
  assert.equal(flag, 1);
  assert.equal(data, 1);
  assert.equal(state.edges.length, 1);
  assert.match(memoryOrderingOutcome(state), /guarantees this/);
});

test("memory-ordering lab (relaxed-counter): relaxed RMW is always correct regardless of interleaving", () => {
  const state = runToEnd("relaxed-counter", "relaxed");
  assert.equal(state.memory.counter, 4);
  assert.match(memoryOrderingOutcome(state), /Final counter = 4/);
});

test("memory-ordering lab (store-buffering, relaxed): both threads can observe 0 for the other's write", () => {
  const state = runToEnd("store-buffering", "relaxed");
  const sawY = state.observations.find(o => o.thread === 0 && o.var === "y").value;
  const sawX = state.observations.find(o => o.thread === 1 && o.var === "x").value;
  assert.equal(sawY, 0);
  assert.equal(sawX, 0);
  assert.match(memoryOrderingOutcome(state), /store-buffering outcome/);
});

test("memory-ordering lab (store-buffering, seqcst): both-see-0 is forbidden", () => {
  const state = runToEnd("store-buffering", "seqcst");
  const sawY = state.observations.find(o => o.thread === 0 && o.var === "y").value;
  const sawX = state.observations.find(o => o.thread === 1 && o.var === "x").value;
  assert.ok(sawY === 1 || sawX === 1, "at least one thread must observe the other's write under SeqCst");
  assert.match(memoryOrderingOutcome(state), /SeqCst-consistent/);
});

test("memory-ordering lab: memoryOrderingOutcome is null before the scenario's script completes", () => {
  let state = createMemoryOrderingState("relaxed-counter");
  state = memoryOrderingReducer(state, { type: "NEXT_STEP" });
  assert.equal(memoryOrderingOutcome(state), null);
});

test("memory-ordering lab: SELECT_ORDERING only applies to store-buffering, no-op elsewhere", () => {
  const relaxedCounter = createMemoryOrderingState("relaxed-counter", "relaxed");
  assert.equal(memoryOrderingReducer(relaxedCounter, { type: "SELECT_ORDERING", ordering: "seqcst" }), relaxedCounter);

  const storeBuffering = createMemoryOrderingState("store-buffering", "relaxed");
  const switched = memoryOrderingReducer(storeBuffering, { type: "SELECT_ORDERING", ordering: "seqcst" });
  assert.equal(switched.ordering, "seqcst");
  assert.deepEqual(switched, createMemoryOrderingState("store-buffering", "seqcst"));
});

test("memory-ordering lab: SELECT_ORDERING with the current ordering is a no-op (same reference)", () => {
  const state = createMemoryOrderingState("store-buffering", "relaxed");
  assert.equal(memoryOrderingReducer(state, { type: "SELECT_ORDERING", ordering: "relaxed" }), state);
});

test("memory-ordering lab: PREVIOUS_STEP exactly restores the prior derived state", () => {
  let state = createMemoryOrderingState("broken-publication");
  state = memoryOrderingReducer(state, { type: "NEXT_STEP" });
  const afterStep1 = state;
  state = memoryOrderingReducer(state, { type: "NEXT_STEP" });
  state = memoryOrderingReducer(state, { type: "PREVIOUS_STEP" });
  assert.deepEqual(state, afterStep1);
});

test("memory-ordering lab: NEXT_STEP/PREVIOUS_STEP are no-ops at the ends of the script (same reference)", () => {
  const initial = createMemoryOrderingState("relaxed-counter");
  assert.equal(memoryOrderingReducer(initial, { type: "PREVIOUS_STEP" }), initial);
  const atMax = runToEnd("relaxed-counter", "relaxed");
  assert.equal(memoryOrderingReducer(atMax, { type: "NEXT_STEP" }), atMax);
});

test("memory-ordering lab: RESET returns the exact initial state for the current scenario and ordering", () => {
  let state = createMemoryOrderingState("store-buffering", "seqcst");
  state = memoryOrderingReducer(state, { type: "NEXT_STEP" });
  state = memoryOrderingReducer(state, { type: "RESET" });
  assert.deepEqual(state, createMemoryOrderingState("store-buffering", "seqcst"));
});

test("memory-ordering lab: SELECT_SCENARIO switches scenario and resets progress", () => {
  let state = createMemoryOrderingState("broken-publication");
  state = memoryOrderingReducer(state, { type: "NEXT_STEP" });
  state = memoryOrderingReducer(state, { type: "SELECT_SCENARIO", scenario: "relaxed-counter" });
  assert.deepEqual(state, createMemoryOrderingState("relaxed-counter", state.ordering));
});

test("memory-ordering lab: SELECT_SCENARIO with the current scenario is a no-op (same reference)", () => {
  const state = createMemoryOrderingState("broken-publication");
  assert.equal(memoryOrderingReducer(state, { type: "SELECT_SCENARIO", scenario: "broken-publication" }), state);
});

test("memory-ordering lab: unknown event is a no-op (same reference)", () => {
  const state = createMemoryOrderingState("broken-publication");
  assert.equal(memoryOrderingReducer(state, { type: "NOPE" }), state);
});

test("memory-ordering lab: event label includes the step number and description", () => {
  const state = memoryOrderingReducer(createMemoryOrderingState("relaxed-counter"), { type: "NEXT_STEP" });
  assert.equal(memoryOrderingEventLabel(state.log[0]), `Step 1: ${state.log[0].text}`);
});

test("memory-ordering lab: announce reports the outcome, reset, scenario change, ordering change, and unknown", () => {
  const state = runToEnd("relaxed-counter", "relaxed");
  assert.match(announceMemoryOrdering(state, { type: "NEXT_STEP" }), /Final counter = 4/);
  assert.equal(announceMemoryOrdering(state, { type: "RESET" }), "Reset. No instructions executed, memory empty.");
  assert.match(announceMemoryOrdering(state, { type: "SELECT_SCENARIO" }), /Scenario changed to/);
  assert.match(announceMemoryOrdering(state, { type: "SELECT_ORDERING" }), /Ordering changed to/);
  assert.equal(announceMemoryOrdering(state, { type: "NOPE" }), null);
});

// --- cas-contention lab (interactive CAS-retry contention model) ---

function runCasToEnd(scenario) {
  let state = createCasState(scenario);
  const max = casScenarioMaxSteps(scenario);
  for (let i = 0; i < max; i++) state = casReducer(state, { type: "NEXT_STEP" });
  return state;
}

test("cas-contention lab: initial state has one contender per scenario config, zero counters", () => {
  const state = createCasState("many-contenders");
  assert.equal(state.contenders.length, 4);
  assert.ok(state.contenders.every(c => c.known === 0 && c.successes === 0 && !c.done));
  assert.equal(state.value, 0);
  assert.equal(state.attempts, 0);
  assert.equal(state.successfulCas, 0);
  assert.equal(state.failedCas, 0);
  assert.equal(state.retries, 0);
  assert.equal(state.ownershipTransfers, 0);
  assert.equal(state.completionStep, null);
  assert.equal(state.step, 0);
});

test("cas-contention lab: every scenario id is a valid initial-state scenario", () => {
  for (const scenario of casScenarios) {
    const state = createCasState(scenario.id);
    assert.equal(state.scenario, scenario.id);
  }
});

test("cas-contention lab (single thread): no contention, every attempt succeeds", () => {
  const state = runCasToEnd("single-thread");
  assert.equal(state.value, 3);
  assert.equal(state.successfulCas, 3);
  assert.equal(state.failedCas, 0);
  assert.equal(state.ownershipTransfers, 0);
  assert.ok(state.contenders[0].done);
});

test("cas-contention lab (two contenders): contention produces at least one failure and one ownership transfer", () => {
  const state = runCasToEnd("two-contenders");
  assert.equal(state.value, 4);
  assert.equal(state.successfulCas, 4);
  assert.equal(state.failedCas, 2);
  assert.equal(state.retries, 2);
  assert.equal(state.ownershipTransfers, 1);
  assert.ok(state.contenders.every(c => c.done));
});

test("cas-contention lab: many-contenders has a higher failure rate than two-contenders (contention collapse)", () => {
  const two = runCasToEnd("two-contenders");
  const many = runCasToEnd("many-contenders");
  const twoFailureRate = two.failedCas / (two.successfulCas + two.failedCas);
  const manyFailureRate = many.failedCas / (many.successfulCas + many.failedCas);
  assert.ok(manyFailureRate > twoFailureRate, `expected many-contenders' failure rate (${manyFailureRate}) to exceed two-contenders' (${twoFailureRate})`);
});

test("cas-contention lab: fixed and exponential backoff both reduce failures below many-contenders' no-backoff baseline", () => {
  const noBackoff = runCasToEnd("many-contenders");
  const fixed = runCasToEnd("fixed-backoff");
  const exponential = runCasToEnd("exponential-backoff");
  assert.ok(fixed.failedCas < noBackoff.failedCas, "fixed backoff should reduce failures vs. no backoff");
  assert.ok(exponential.failedCas < noBackoff.failedCas, "exponential backoff should reduce failures vs. no backoff");
  assert.equal(fixed.successfulCas, noBackoff.successfulCas);
  assert.equal(exponential.successfulCas, noBackoff.successfulCas);
});

test("cas-contention lab (single-writer comparison): zero contention, zero failures, zero ownership transfers", () => {
  const state = runCasToEnd("single-writer");
  assert.equal(state.value, 8);
  assert.equal(state.successfulCas, 8);
  assert.equal(state.failedCas, 0);
  assert.equal(state.ownershipTransfers, 0);
  assert.equal(state.completionStep, 8);
});

test("cas-contention lab: PREVIOUS_STEP exactly restores the prior derived state", () => {
  let state = createCasState("two-contenders");
  state = casReducer(state, { type: "NEXT_STEP" });
  const afterStep1 = state;
  state = casReducer(state, { type: "NEXT_STEP" });
  state = casReducer(state, { type: "PREVIOUS_STEP" });
  assert.deepEqual(state, afterStep1);
});

test("cas-contention lab: NEXT_STEP/PREVIOUS_STEP are no-ops at the ends of the run (same reference)", () => {
  const initial = createCasState("single-thread");
  assert.equal(casReducer(initial, { type: "PREVIOUS_STEP" }), initial);
  const atMax = runCasToEnd("single-thread");
  assert.equal(casReducer(atMax, { type: "NEXT_STEP" }), atMax);
});

test("cas-contention lab: RESET returns the exact initial state for the current scenario", () => {
  let state = createCasState("many-contenders");
  state = casReducer(state, { type: "NEXT_STEP" });
  state = casReducer(state, { type: "RESET" });
  assert.deepEqual(state, createCasState("many-contenders"));
});

test("cas-contention lab: SELECT_SCENARIO switches scenario and resets progress", () => {
  let state = createCasState("single-thread");
  state = casReducer(state, { type: "NEXT_STEP" });
  state = casReducer(state, { type: "SELECT_SCENARIO", scenario: "many-contenders" });
  assert.deepEqual(state, createCasState("many-contenders"));
});

test("cas-contention lab: SELECT_SCENARIO with the current scenario is a no-op (same reference)", () => {
  const state = createCasState("single-thread");
  assert.equal(casReducer(state, { type: "SELECT_SCENARIO", scenario: "single-thread" }), state);
});

test("cas-contention lab: unknown event is a no-op (same reference)", () => {
  const state = createCasState("single-thread");
  assert.equal(casReducer(state, { type: "NOPE" }), state);
});

test("cas-contention lab: event label includes the step number and description", () => {
  const state = casReducer(createCasState("single-thread"), { type: "NEXT_STEP" });
  assert.equal(casEventLabel(state.log[0]), `Step 1: ${state.log[0].text}`);
});

test("cas-contention lab: announce reports value/counters after a step, and reset/scenario-change/unknown", () => {
  const state = casReducer(createCasState("single-thread"), { type: "NEXT_STEP" });
  assert.match(announceCas(state, { type: "NEXT_STEP" }), /Value: 1\. Successful: 1/);
  assert.equal(announceCas(state, { type: "RESET" }), "Reset. No attempts, value 0, no contenders have completed.");
  assert.match(announceCas(state, { type: "SELECT_SCENARIO" }), /Scenario changed to/);
  assert.equal(announceCas(state, { type: "NOPE" }), null);
});

// --- spsc-ring-buffer lab (interactive SPSC ring-buffer model) ---

function runRingBufferToEnd(scenario) {
  let state = createSpscRingBufferState(scenario);
  const max = spscRingBufferScenarioMaxSteps(scenario);
  for (let i = 0; i < max; i++) state = spscRingBufferReducer(state, { type: "NEXT_STEP" });
  return state;
}

test("spsc-ring-buffer lab: initial state is empty with all cursors at zero", () => {
  const state = createSpscRingBufferState("normal");
  assert.equal(state.reserveIndex, 0);
  assert.equal(state.head, 0);
  assert.equal(state.readIndex, 0);
  assert.equal(state.tail, 0);
  assert.equal(state.step, 0);
  assert.equal(state.log.length, 0);
  assert.ok(state.slots.every(s => s.value === null && s.published === false));
});

test("spsc-ring-buffer lab: every scenario id is a valid initial-state scenario", () => {
  for (const scenario of spscRingBufferScenarios) {
    const state = createSpscRingBufferState(scenario.id);
    assert.equal(state.scenario, scenario.id);
  }
});

test("spsc-ring-buffer lab (normal): two full cycles complete with no rejections or starves", () => {
  const state = runRingBufferToEnd("normal");
  assert.equal(state.reserveIndex, 2);
  assert.equal(state.head, 2);
  assert.equal(state.readIndex, 2);
  assert.equal(state.tail, 2);
  assert.equal(state.rejectedReservations, 0);
  assert.equal(state.starvedReads, 0);
  assert.equal(state.overwrites, 0);
  assert.equal(state.incorrectReads, 0);
});

test("spsc-ring-buffer lab (wrap-around): the final produce reuses slot 0 after it was freed", () => {
  const state = runRingBufferToEnd("wrap-around");
  assert.equal(state.reserveIndex, 3);
  assert.equal(state.tail, 2);
  assert.equal(state.slots[0].value, 2, "slot 0 should hold the third produced value after wrapping");
  assert.equal(state.slots[0].published, true);
  assert.equal(state.overwrites, 0, "reusing a freed slot must not count as an overwrite bug");
});

test("spsc-ring-buffer lab (full): the third reservation on a 2-slot buffer is rejected", () => {
  const state = runRingBufferToEnd("full");
  assert.equal(state.rejectedReservations, 1);
  assert.equal(state.head, 2);
  assert.equal(state.tail, 0);
});

test("spsc-ring-buffer lab (empty): the first read on an empty buffer is starved", () => {
  const state = runRingBufferToEnd("empty");
  assert.equal(state.starvedReads, 1);
  assert.equal(state.head, 1);
  assert.equal(state.tail, 1);
});

test("spsc-ring-buffer lab (cached-cursor): the 5th reservation forces a producer cache refresh", () => {
  const state = runRingBufferToEnd("cached-cursor");
  assert.equal(state.producerCacheHits, 4);
  assert.equal(state.producerCacheRefreshes, 1);
  assert.equal(state.consumerCacheHits, 1);
  assert.equal(state.consumerCacheRefreshes, 1);
  assert.equal(state.rejectedReservations, 0);
});

test("spsc-ring-buffer lab (batch): one batch publish and one batch ack replace three individual ones", () => {
  const state = runRingBufferToEnd("batch");
  assert.equal(state.batchPublishes, 1);
  assert.equal(state.batchAcks, 1);
  assert.equal(state.singlePublishes, 0);
  assert.equal(state.singleAcks, 0);
  assert.equal(state.head, 3);
  assert.equal(state.tail, 3);
});

test("spsc-ring-buffer lab (bug: publish before write): consumer reads stale pre-seeded data", () => {
  const state = runRingBufferToEnd("bug-ordering");
  assert.equal(state.incorrectReads, 1);
  const readEntry = state.log.find(e => e.kind === "read");
  assert.match(readEntry.text, /BUG: this is stale leftover data/);
});

test("spsc-ring-buffer lab (bug: overwrite unconsumed): the 3rd write on a full 2-slot buffer overwrites slot 0", () => {
  const state = runRingBufferToEnd("bug-overwrite");
  assert.equal(state.overwrites, 1);
  const overwriteEntry = state.log.find(e => e.overwrite === true);
  assert.match(overwriteEntry.text, /OVERWRITE BUG/);
});

test("spsc-ring-buffer lab: PREVIOUS_STEP exactly restores the prior derived state", () => {
  let state = createSpscRingBufferState("normal");
  state = spscRingBufferReducer(state, { type: "NEXT_STEP" });
  const afterStep1 = state;
  state = spscRingBufferReducer(state, { type: "NEXT_STEP" });
  state = spscRingBufferReducer(state, { type: "PREVIOUS_STEP" });
  assert.deepEqual(state, afterStep1);
});

test("spsc-ring-buffer lab: NEXT_STEP/PREVIOUS_STEP are no-ops at the ends of the run (same reference)", () => {
  const initial = createSpscRingBufferState("normal");
  assert.equal(spscRingBufferReducer(initial, { type: "PREVIOUS_STEP" }), initial);
  const atMax = runRingBufferToEnd("normal");
  assert.equal(spscRingBufferReducer(atMax, { type: "NEXT_STEP" }), atMax);
});

test("spsc-ring-buffer lab: RESET returns the exact initial state for the current scenario", () => {
  let state = createSpscRingBufferState("full");
  state = spscRingBufferReducer(state, { type: "NEXT_STEP" });
  state = spscRingBufferReducer(state, { type: "RESET" });
  assert.deepEqual(state, createSpscRingBufferState("full"));
});

test("spsc-ring-buffer lab: SELECT_SCENARIO switches scenario and resets progress", () => {
  let state = createSpscRingBufferState("normal");
  state = spscRingBufferReducer(state, { type: "NEXT_STEP" });
  state = spscRingBufferReducer(state, { type: "SELECT_SCENARIO", scenario: "full" });
  assert.deepEqual(state, createSpscRingBufferState("full"));
});

test("spsc-ring-buffer lab: SELECT_SCENARIO with the current scenario is a no-op (same reference)", () => {
  const state = createSpscRingBufferState("normal");
  assert.equal(spscRingBufferReducer(state, { type: "SELECT_SCENARIO", scenario: "normal" }), state);
});

test("spsc-ring-buffer lab: unknown event is a no-op (same reference)", () => {
  const state = createSpscRingBufferState("normal");
  assert.equal(spscRingBufferReducer(state, { type: "NOPE" }), state);
});

test("spsc-ring-buffer lab: event label includes the step number and description", () => {
  const state = spscRingBufferReducer(createSpscRingBufferState("normal"), { type: "NEXT_STEP" });
  assert.equal(spscRingBufferEventLabel(state.log[0]), `Step 1: ${state.log[0].text}`);
});

test("spsc-ring-buffer lab: announce reports occupancy/counters after a step, and reset/scenario-change/unknown", () => {
  const state = spscRingBufferReducer(createSpscRingBufferState("normal"), { type: "NEXT_STEP" });
  assert.match(announceSpscRingBuffer(state, { type: "NEXT_STEP" }), /Occupancy: 0\. Rejected: 0\. Starved: 0\./);
  assert.equal(announceSpscRingBuffer(state, { type: "RESET" }), "Reset. Buffer empty, all cursors at zero.");
  assert.match(announceSpscRingBuffer(state, { type: "SELECT_SCENARIO" }), /Scenario changed to/);
  assert.equal(announceSpscRingBuffer(state, { type: "NOPE" }), null);
});

// --- thread-per-core lab (interactive worker-pool vs. owned-partition model) ---

function runThreadPerCoreToEnd(scenario) {
  let state = createThreadPerCoreState(scenario);
  const max = threadPerCoreScenarioMaxSteps(scenario);
  for (let i = 0; i < max; i++) state = threadPerCoreReducer(state, { type: "NEXT_STEP" });
  return state;
}

test("thread-per-core lab: initial state has 4 idle cores and zero counters", () => {
  const state = createThreadPerCoreState("worker-pool");
  assert.equal(state.cores.length, 4);
  assert.ok(state.cores.every(c => c.processed === 0 && c.queue.length === 0 && !c.migrated));
  assert.equal(state.totalRequests, 0);
  assert.equal(state.lockAcquisitions, 0);
  assert.equal(state.handoffs, 0);
  assert.equal(state.rejectedRequests, 0);
  assert.equal(state.migrationEvents, 0);
});

test("thread-per-core lab: every scenario id is a valid initial-state scenario", () => {
  for (const scenario of threadPerCoreScenarios) {
    const state = createThreadPerCoreState(scenario.id);
    assert.equal(state.scenario, scenario.id);
  }
});

test("thread-per-core lab (worker-pool): 4 requests take 4 turns, one lock acquisition each, no handoffs", () => {
  const state = runThreadPerCoreToEnd("worker-pool");
  assert.equal(state.totalRequests, 4);
  assert.equal(state.lockAcquisitions, 4);
  assert.equal(state.handoffs, 0);
  assert.equal(state.rejectedRequests, 0);
  assert.equal(state.cores.reduce((sum, c) => sum + c.processed, 0), 4);
});

test("thread-per-core lab (owned-state): the same 4 requests finish in a single turn with zero lock acquisitions", () => {
  const state = runThreadPerCoreToEnd("owned-state");
  assert.equal(threadPerCoreScenarioMaxSteps("owned-state"), 1);
  assert.equal(state.totalRequests, 4);
  assert.equal(state.lockAcquisitions, 0);
  assert.equal(state.handoffs, 0);
  assert.ok(state.cores.every(c => c.processed === 1), "every core should have processed exactly its own direct request");
});

test("thread-per-core lab (cross-core-handoff): 4 handoffs cost one extra turn versus owned-state", () => {
  const state = runThreadPerCoreToEnd("cross-core-handoff");
  assert.equal(threadPerCoreScenarioMaxSteps("cross-core-handoff"), 2, "handoff should take one more turn than direct dispatch");
  assert.equal(state.handoffs, 4);
  assert.equal(state.totalRequests, 4);
  assert.ok(state.cores.every(c => c.processed === 1));
});

test("thread-per-core lab (hot-partition): core 1 is overloaded and rejects while other cores stay idle", () => {
  const state = runThreadPerCoreToEnd("hot-partition");
  assert.equal(state.rejectedRequests, 2);
  assert.equal(state.cores[1].processed, 4);
  assert.ok(state.cores.filter(c => c.id !== 1).every(c => c.processed === 0), "cores 0, 2, and 3 should never process anything in this scenario");
});

test("thread-per-core lab (scheduler-migration): requests are still processed correctly across a migration event", () => {
  const state = runThreadPerCoreToEnd("scheduler-migration");
  assert.equal(state.migrationEvents, 1);
  assert.equal(state.cores[2].migrated, true);
  assert.equal(state.cores[2].currentCpu, 5);
  assert.ok(state.cores.every(c => c.processed === 2), "every core should have processed both its pre- and post-migration request");
});

test("thread-per-core lab (backpressure): a 5-request burst against a 3-slot inbox rejects exactly 2", () => {
  const state = runThreadPerCoreToEnd("backpressure");
  assert.equal(state.rejectedRequests, 2);
  assert.equal(state.cores[0].processed, 3);
});

test("thread-per-core lab: PREVIOUS_STEP exactly restores the prior derived state", () => {
  let state = createThreadPerCoreState("hot-partition");
  state = threadPerCoreReducer(state, { type: "NEXT_STEP" });
  const afterStep1 = state;
  state = threadPerCoreReducer(state, { type: "NEXT_STEP" });
  state = threadPerCoreReducer(state, { type: "PREVIOUS_STEP" });
  assert.deepEqual(state, afterStep1);
});

test("thread-per-core lab: NEXT_STEP/PREVIOUS_STEP are no-ops at the ends of the run (same reference)", () => {
  const initial = createThreadPerCoreState("worker-pool");
  assert.equal(threadPerCoreReducer(initial, { type: "PREVIOUS_STEP" }), initial);
  const atMax = runThreadPerCoreToEnd("worker-pool");
  assert.equal(threadPerCoreReducer(atMax, { type: "NEXT_STEP" }), atMax);
});

test("thread-per-core lab: RESET returns the exact initial state for the current scenario", () => {
  let state = createThreadPerCoreState("hot-partition");
  state = threadPerCoreReducer(state, { type: "NEXT_STEP" });
  state = threadPerCoreReducer(state, { type: "RESET" });
  assert.deepEqual(state, createThreadPerCoreState("hot-partition"));
});

test("thread-per-core lab: SELECT_SCENARIO switches scenario and resets progress", () => {
  let state = createThreadPerCoreState("worker-pool");
  state = threadPerCoreReducer(state, { type: "NEXT_STEP" });
  state = threadPerCoreReducer(state, { type: "SELECT_SCENARIO", scenario: "hot-partition" });
  assert.deepEqual(state, createThreadPerCoreState("hot-partition"));
});

test("thread-per-core lab: SELECT_SCENARIO with the current scenario is a no-op (same reference)", () => {
  const state = createThreadPerCoreState("worker-pool");
  assert.equal(threadPerCoreReducer(state, { type: "SELECT_SCENARIO", scenario: "worker-pool" }), state);
});

test("thread-per-core lab: unknown event is a no-op (same reference)", () => {
  const state = createThreadPerCoreState("worker-pool");
  assert.equal(threadPerCoreReducer(state, { type: "NOPE" }), state);
});

test("thread-per-core lab: event label includes the step number and description", () => {
  const state = threadPerCoreReducer(createThreadPerCoreState("worker-pool"), { type: "NEXT_STEP" });
  assert.equal(threadPerCoreEventLabel(state.log[0]), `Step 1: ${state.log[0].text}`);
});

test("thread-per-core lab: announce reports handoffs/rejected/migrations after a step, and reset/scenario-change/unknown", () => {
  const state = threadPerCoreReducer(createThreadPerCoreState("worker-pool"), { type: "NEXT_STEP" });
  assert.match(announceThreadPerCore(state, { type: "NEXT_STEP" }), /Handoffs: 0\. Rejected: 0\. Migrations: 0\./);
  assert.equal(announceThreadPerCore(state, { type: "RESET" }), "Reset. No requests, no handoffs, no migrations.");
  assert.match(announceThreadPerCore(state, { type: "SELECT_SCENARIO" }), /Scenario changed to/);
  assert.equal(announceThreadPerCore(state, { type: "NOPE" }), null);
});
