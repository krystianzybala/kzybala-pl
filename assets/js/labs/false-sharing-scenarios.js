// Pure state/reducer for the false-sharing lab's interactive cache-line
// model — no DOM access, so it's directly unit-testable (spec.md "Pure
// transitions"). Contract: openspec/changes/plab-010-false-sharing/design.md.
//
// Deliberately simplified vs. real MESI (three states, not five/seven) and
// explicitly conceptual — see the .disclosure.conceptual block on the lab
// page and docs/components.md.

export const falseSharingScenarios = [
  { id: "shared-line", label: "Shared line" },
  { id: "padded-line", label: "Padded line" },
  { id: "read-mostly", label: "Read-mostly" },
];

export const falseSharingEvents = [
  "CPU0_WRITE", "CPU1_WRITE", "CPU0_READ", "CPU1_READ",
  "NEXT_STEP", "PREVIOUS_STEP", "RESET", "SELECT_SCENARIO",
];

function scenarioLabel(id) {
  return falseSharingScenarios.find(s => s.id === id)?.label ?? id;
}

export function createFalseSharingState(scenario = falseSharingScenarios[0].id) {
  return {
    scenario,
    step: 0,
    history: [],
    cpu0: { operation: "idle", lineState: "shared" },
    cpu1: { operation: "idle", lineState: "shared" },
    owner: null,
    invalidations: 0,
    transfers: 0,
  };
}

export const falseSharingInitialState = createFalseSharingState();

// Applies one CPUx_WRITE/CPUx_READ op to a state, per the scenario's memory
// layout. `padded-line` gives each CPU its own coherence unit, so writes on
// one core never invalidate the other — that is the entire point of padding.
function applyOp(state, opType) {
  const padded = state.scenario === "padded-line";
  const isWrite = opType === "CPU0_WRITE" || opType === "CPU1_WRITE";
  const actor = opType.startsWith("CPU0") ? 0 : 1;
  const other = 1 - actor;
  const cpus = [{ ...state.cpu0 }, { ...state.cpu1 }];
  let owner = state.owner;
  let invalidations = state.invalidations;
  let transfers = state.transfers;

  if (padded) {
    cpus[actor].operation = isWrite ? "write" : "read";
    cpus[actor].lineState = isWrite ? "modified" : "shared";
    cpus[other].operation = "idle";
  } else if (isWrite) {
    const alreadyExclusiveOwner = owner === actor && cpus[actor].lineState === "modified";
    if (!alreadyExclusiveOwner) {
      if (cpus[other].lineState !== "invalid") invalidations++;
      if (owner !== null && owner !== actor) transfers++;
      cpus[other].lineState = "invalid";
      cpus[other].operation = "idle";
      cpus[actor].lineState = "modified";
      owner = actor;
    }
    cpus[actor].operation = "write";
  } else {
    if (cpus[actor].lineState === "invalid") {
      transfers++;
      cpus[actor].lineState = "shared";
      if (owner === other) cpus[other].lineState = "shared";
      owner = null;
    }
    cpus[actor].operation = "read";
  }

  return { ...state, cpu0: cpus[0], cpu1: cpus[1], owner, invalidations, transfers };
}

function replay(scenario, ops) {
  let state = createFalseSharingState(scenario);
  for (const op of ops) state = applyOp(state, op);
  return state;
}

export function falseSharingReducer(state, event) {
  switch (event.type) {
    case "CPU0_WRITE":
    case "CPU1_WRITE":
    case "CPU0_READ":
    case "CPU1_READ": {
      const ops = state.history.slice(0, state.step).concat(event.type);
      const derived = replay(state.scenario, ops);
      return { ...derived, history: ops, step: ops.length };
    }
    case "NEXT_STEP": {
      if (state.step >= state.history.length) return state;
      const step = state.step + 1;
      const derived = replay(state.scenario, state.history.slice(0, step));
      return { ...derived, history: state.history, step };
    }
    case "PREVIOUS_STEP": {
      if (state.step <= 0) return state;
      const step = state.step - 1;
      const derived = replay(state.scenario, state.history.slice(0, step));
      return { ...derived, history: state.history, step };
    }
    case "RESET":
      return createFalseSharingState(state.scenario);
    case "SELECT_SCENARIO":
      return event.scenario === state.scenario ? state : createFalseSharingState(event.scenario);
    default:
      return state;
  }
}

// Short label for one history entry — used to render the visible event log
// (design.md "Visual rules": every transition updates an event log).
export function falseSharingEventLabel(opType) {
  const actor = opType.startsWith("CPU0") ? 0 : 1;
  const verb = opType.endsWith("WRITE") ? "wrote" : "read";
  return `CPU ${actor} ${verb}.`;
}

function describeOp(state, opType) {
  const actor = opType.startsWith("CPU0") ? 0 : 1;
  const other = 1 - actor;
  const verb = opType.endsWith("WRITE") ? "wrote" : "read";
  const actorLine = actor === 0 ? state.cpu0.lineState : state.cpu1.lineState;
  const otherLine = other === 0 ? state.cpu0.lineState : state.cpu1.lineState;
  return `CPU ${actor} ${verb}. CPU ${actor} line ${actorLine}, CPU ${other} line ${otherLine}. ` +
    `Invalidations: ${state.invalidations}. Transfers: ${state.transfers}.`;
}

export function announceFalseSharing(state, event) {
  switch (event.type) {
    case "CPU0_WRITE":
    case "CPU1_WRITE":
    case "CPU0_READ":
    case "CPU1_READ":
      return describeOp(state, event.type);
    case "NEXT_STEP":
    case "PREVIOUS_STEP": {
      const last = state.history[state.step - 1];
      return last
        ? `Step ${state.step} of ${state.history.length}. ${describeOp(state, last)}`
        : `Step 0 of ${state.history.length}. Initial state, both lines shared.`;
    }
    case "RESET":
      return "Reset. Both lines shared, no invalidations, no transfers.";
    case "SELECT_SCENARIO":
      return `Scenario changed to ${scenarioLabel(state.scenario)}.`;
    default:
      return null;
  }
}
