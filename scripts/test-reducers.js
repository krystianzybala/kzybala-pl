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
