// Pure state/reducer for the CAS-contention lab's interactive model — no
// DOM access, so it's directly unit-testable (spec.md "Pure transitions").
// Contract: openspec/changes/plab-023-cas-contention-and-backoff/design.md.
//
// Simulates a fixed number of "contenders" retrying a compare-and-set loop
// against one shared value, scheduled in a deterministic round-robin order.
// Contention (and its resulting failures/retries) emerges from the
// mechanics themselves rather than being hand-scripted — see docs/
// components.md and the .disclosure.conceptual block on the lab page for
// why this is a teaching model, not a scheduler simulation.

export const casScenarios = [
  { id: "single-thread", label: "Single thread" },
  { id: "two-contenders", label: "Two contenders" },
  { id: "many-contenders", label: "Many contenders" },
  { id: "fixed-backoff", label: "Fixed backoff" },
  { id: "exponential-backoff", label: "Exponential backoff" },
  { id: "single-writer", label: "Single-writer comparison" },
];

export const casEvents = ["NEXT_STEP", "PREVIOUS_STEP", "RESET", "SELECT_SCENARIO"];

const FIXED_DELAY = 2;
const EXP_CAP = 3;

const SCENARIO_CONFIG = {
  "single-thread": { contenderCount: 1, targetPerContender: 3, backoff: "none" },
  "two-contenders": { contenderCount: 2, targetPerContender: 2, backoff: "none" },
  "many-contenders": { contenderCount: 4, targetPerContender: 2, backoff: "none" },
  "fixed-backoff": { contenderCount: 4, targetPerContender: 2, backoff: "fixed" },
  "exponential-backoff": { contenderCount: 4, targetPerContender: 2, backoff: "exponential" },
  "single-writer": { contenderCount: 1, targetPerContender: 8, backoff: "none", singleWriter: true },
};

const MAX_SAFETY_STEPS = 500;

function scenarioLabel(id) {
  return casScenarios.find(s => s.id === id)?.label ?? id;
}

function createContenders(count) {
  return Array.from({ length: count }, (_, id) => ({
    id, known: 0, successes: 0, failures: 0, failureStreak: 0, backoffWait: 0, done: false,
  }));
}

export function createCasState(scenario = casScenarios[0].id) {
  return {
    scenario,
    step: 0,
    value: 0,
    contenders: createContenders(SCENARIO_CONFIG[scenario].contenderCount),
    attempts: 0,
    successfulCas: 0,
    failedCas: 0,
    retries: 0,
    ownershipTransfers: 0,
    lastWinner: null,
    completionStep: null,
    turnCursor: 0,
    log: [],
  };
}

export const casInitialState = createCasState();

// Deterministic "jitter": a small, reproducible offset derived from the
// contender id and its current failure streak — not real randomness, so
// replaying the same scenario always produces the same trace (see
// docs/lab-framework.md "Pure transitions" and cache-hierarchy-scenarios.js
// for the same reproducibility rationale applied to its own pseudo-random
// access order). Trims, rather than adds to, the base delay — jitter's job
// is to stop contenders who failed in the same round from all becoming
// eligible again on the exact same future step and re-colliding in lockstep.
function jitterFor(contenderId, failureStreak) {
  return -((contenderId + failureStreak) % 2);
}

// Applies exactly one scheduled turn to the running (mutable, replay-local)
// simulation. Returns a descriptive log entry, or null if the scenario has
// already fully completed (nothing left to schedule).
//
// Backing-off contenders are excluded from the round-robin rotation
// entirely (their delay ticks down every step, in parallel with whichever
// other contender is attempting) rather than merely consuming a no-op turn
// in line — otherwise backoff would only add elapsed steps without ever
// changing which contenders actually collide, defeating the point of it.
function applyTurn(sim, cfg, index) {
  for (const c of sim.contenders) {
    if (!c.done && c.backoffWait > 0) c.backoffWait--;
  }

  const eligible = sim.contenders.filter(c => !c.done && c.backoffWait === 0);
  if (eligible.length === 0) {
    if (sim.contenders.every(c => c.done)) return null;
    return { step: index + 1, contender: null, kind: "tick", text: "All active contenders are backing off." };
  }

  const contender = eligible[sim.turnCursor % eligible.length];
  sim.turnCursor++;

  sim.attempts++;
  const succeeds = cfg.singleWriter || contender.known === sim.value;

  if (succeeds) {
    sim.value++;
    contender.known = sim.value;
    contender.successes++;
    contender.failureStreak = 0;
    sim.successfulCas++;
    if (sim.lastWinner !== null && sim.lastWinner !== contender.id) sim.ownershipTransfers++;
    sim.lastWinner = contender.id;
    if (contender.successes >= cfg.targetPerContender) contender.done = true;
    if (sim.contenders.every(c => c.done) && sim.completionStep === null) sim.completionStep = index + 1;
    return { step: index + 1, contender: contender.id, kind: "success", text: `T${contender.id} CAS(${contender.known - 1} → ${contender.known}) succeeds.` };
  }

  sim.failedCas++;
  sim.retries++;
  contender.failureStreak++;
  const staleKnown = contender.known;
  contender.known = sim.value;
  if (cfg.backoff === "fixed") {
    contender.backoffWait = FIXED_DELAY;
  } else if (cfg.backoff === "exponential") {
    const base = Math.min(2 ** (contender.failureStreak - 1), EXP_CAP);
    contender.backoffWait = Math.max(0, base + jitterFor(contender.id, contender.failureStreak));
  }
  return { step: index + 1, contender: contender.id, kind: "failure", text: `T${contender.id} CAS(expected ${staleKnown}) fails — value is already ${sim.value}. Retrying.` };
}

function createSim(scenarioId) {
  return {
    value: 0,
    contenders: createContenders(SCENARIO_CONFIG[scenarioId].contenderCount),
    attempts: 0,
    successfulCas: 0,
    failedCas: 0,
    retries: 0,
    ownershipTransfers: 0,
    lastWinner: null,
    completionStep: null,
    turnCursor: 0,
  };
}

// Runs the full deterministic simulation for a scenario and returns its
// total step count (the point every contender reaches its target) — pure
// and reproducible, so it only ever needs computing once per scenario id.
const maxStepsCache = new Map();
export function casScenarioMaxSteps(scenarioId) {
  if (maxStepsCache.has(scenarioId)) return maxStepsCache.get(scenarioId);
  const cfg = SCENARIO_CONFIG[scenarioId];
  const sim = createSim(scenarioId);
  let index = 0;
  while (index < MAX_SAFETY_STEPS) {
    const entry = applyTurn(sim, cfg, index);
    if (!entry) break;
    index++;
  }
  maxStepsCache.set(scenarioId, index);
  return index;
}

// Replays scenario from scratch up to (not including) step `upToStep` — the
// same replay-from-history pattern as the other labs' scenario reducers.
function deriveState(scenarioId, upToStep) {
  const cfg = SCENARIO_CONFIG[scenarioId];
  const sim = createSim(scenarioId);
  const log = [];
  for (let i = 0; i < upToStep; i++) {
    const entry = applyTurn(sim, cfg, i);
    if (!entry) break;
    log.push(entry);
  }
  return {
    scenario: scenarioId,
    step: upToStep,
    value: sim.value,
    contenders: sim.contenders,
    attempts: sim.attempts,
    successfulCas: sim.successfulCas,
    failedCas: sim.failedCas,
    retries: sim.retries,
    ownershipTransfers: sim.ownershipTransfers,
    lastWinner: sim.lastWinner,
    completionStep: sim.completionStep,
    turnCursor: sim.turnCursor,
    log,
  };
}

export function casReducer(state, event) {
  switch (event.type) {
    case "NEXT_STEP": {
      const max = casScenarioMaxSteps(state.scenario);
      if (state.step >= max) return state;
      return deriveState(state.scenario, state.step + 1);
    }
    case "PREVIOUS_STEP":
      if (state.step <= 0) return state;
      return deriveState(state.scenario, state.step - 1);
    case "RESET":
      return createCasState(state.scenario);
    case "SELECT_SCENARIO":
      return event.scenario === state.scenario ? state : createCasState(event.scenario);
    default:
      return state;
  }
}

// Short label for one log entry — used to render the visible event log.
export function casEventLabel(entry) {
  return `Step ${entry.step}: ${entry.text}`;
}

export function announceCas(state, event) {
  switch (event.type) {
    case "NEXT_STEP":
    case "PREVIOUS_STEP": {
      const last = state.log[state.log.length - 1];
      return last
        ? `${casEventLabel(last)} Value: ${state.value}. Successful: ${state.successfulCas}. Failed: ${state.failedCas}. Ownership transfers: ${state.ownershipTransfers}.`
        : "Step 0. No CAS attempts yet.";
    }
    case "RESET":
      return "Reset. No attempts, value 0, no contenders have completed.";
    case "SELECT_SCENARIO":
      return `Scenario changed to ${scenarioLabel(state.scenario)}.`;
    default:
      return null;
  }
}
