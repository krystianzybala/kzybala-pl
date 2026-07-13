// Pure state/reducer for the cache-coherence/MESI lab's interactive model —
// no DOM access, so it's directly unit-testable (spec.md "Pure transitions").
// Contract: openspec/changes/plab-021-cache-coherence-mesi/design.md.
//
// Models the textbook four-state MESI protocol (Modified/Exclusive/Shared/
// Invalid) for one conceptual memory line shared by two CPUs — deliberately
// simplified vs. any real coherence protocol and explicitly conceptual, see
// the .disclosure.conceptual block on the lab page and docs/components.md.

export const mesiScenarios = [
  { id: "single-reader", label: "Single reader" },
  { id: "two-readers", label: "Two readers" },
  { id: "reader-then-writer", label: "Reader then writer" },
  { id: "competing-writers", label: "Competing writers" },
  { id: "eviction-writeback", label: "Eviction & write-back" },
];

export const mesiEvents = [
  "CPU0_READ", "CPU1_READ", "CPU0_WRITE", "CPU1_WRITE", "CPU0_EVICT", "CPU1_EVICT",
  "NEXT_STEP", "PREVIOUS_STEP", "RESET", "SELECT_SCENARIO",
];

function scenarioLabel(id) {
  return mesiScenarios.find(s => s.id === id)?.label ?? id;
}

export function createMesiState(scenario = mesiScenarios[0].id) {
  return {
    scenario,
    step: 0,
    history: [],
    memoryValue: 0,
    cpu0: { state: "Invalid", value: null },
    cpu1: { state: "Invalid", value: null },
    owner: null,
    invalidations: 0,
    transfers: 0,
    writeBacks: 0,
  };
}

export const mesiInitialState = createMesiState();

function actorOf(opType) {
  return opType.startsWith("CPU0") ? 0 : 1;
}

function currentOwner(cpu0, cpu1) {
  if (cpu0.state === "Modified" || cpu0.state === "Exclusive") return 0;
  if (cpu1.state === "Modified" || cpu1.state === "Exclusive") return 1;
  return null;
}

// Applies one CPUx_READ/CPUx_WRITE/CPUx_EVICT op to a state, following the
// textbook MESI transition table. Returns the fully derived next state
// (memoryValue/cpuN/owner/counters) — see design.md "State model".
function applyOp(state, opType) {
  const actor = actorOf(opType);
  const other = 1 - actor;
  const cpus = [{ ...state.cpu0 }, { ...state.cpu1 }];
  let memoryValue = state.memoryValue;
  let invalidations = state.invalidations;
  let transfers = state.transfers;
  let writeBacks = state.writeBacks;

  if (opType.endsWith("READ")) {
    if (cpus[actor].state !== "Invalid") {
      // Read hit — state unchanged, no bus transaction.
    } else if (cpus[other].state === "Modified") {
      // The dirty holder must supply its value (cache-to-cache transfer)
      // and flush it to memory (write-back) — memory was stale.
      memoryValue = cpus[other].value;
      writeBacks++;
      transfers++;
      cpus[other] = { state: "Shared", value: cpus[other].value };
      cpus[actor] = { state: "Shared", value: cpus[other].value };
    } else if (cpus[other].state === "Shared" || cpus[other].state === "Exclusive") {
      // Cache-to-cache transfer from a clean holder; a lone Exclusive
      // holder downgrades to Shared now that a second reader exists.
      transfers++;
      const value = cpus[other].value;
      cpus[other] = { state: "Shared", value };
      cpus[actor] = { state: "Shared", value };
    } else {
      // No other cache holds the line — fetch from memory, take it
      // Exclusive since nobody else needs to be told about this copy.
      cpus[actor] = { state: "Exclusive", value: memoryValue };
    }
  } else if (opType.endsWith("WRITE")) {
    if (cpus[actor].state === "Modified") {
      // Write hit — already the sole dirty owner.
      cpus[actor] = { state: "Modified", value: cpus[actor].value + 1 };
    } else if (cpus[actor].state === "Exclusive") {
      // Silent upgrade — no bus transaction, nobody else holds a copy.
      cpus[actor] = { state: "Modified", value: cpus[actor].value + 1 };
    } else if (cpus[other].state === "Modified") {
      // Read-for-ownership against a dirty holder: transfer + write-back
      // the current value, invalidate the holder, then overwrite.
      writeBacks++;
      transfers++;
      invalidations++;
      const newValue = cpus[other].value + 1;
      memoryValue = cpus[other].value;
      cpus[other] = { state: "Invalid", value: null };
      cpus[actor] = { state: "Modified", value: newValue };
    } else if (cpus[other].state === "Shared" || cpus[other].state === "Exclusive") {
      // Read-for-ownership (or a bus-upgrade if the actor already held
      // this line Shared) against a clean holder: invalidate it.
      invalidations++;
      const hadCopy = cpus[actor].state === "Shared";
      if (!hadCopy) transfers++;
      const baseValue = hadCopy ? cpus[actor].value : cpus[other].value;
      cpus[other] = { state: "Invalid", value: null };
      cpus[actor] = { state: "Modified", value: baseValue + 1 };
    } else {
      // Nobody else holds the line — fetch from memory (or reuse the
      // actor's own Shared copy) and become the sole Modified owner.
      const hadCopy = cpus[actor].state === "Shared";
      const baseValue = hadCopy ? cpus[actor].value : memoryValue;
      cpus[actor] = { state: "Modified", value: baseValue + 1 };
    }
  } else if (opType.endsWith("EVICT")) {
    if (cpus[actor].state === "Modified") {
      memoryValue = cpus[actor].value;
      writeBacks++;
      cpus[actor] = { state: "Invalid", value: null };
    } else if (cpus[actor].state !== "Invalid") {
      // Exclusive/Shared — clean, memory already has the current value.
      cpus[actor] = { state: "Invalid", value: null };
    }
  }

  return {
    ...state,
    cpu0: cpus[0],
    cpu1: cpus[1],
    memoryValue,
    owner: currentOwner(cpus[0], cpus[1]),
    invalidations,
    transfers,
    writeBacks,
  };
}

function replay(scenario, ops) {
  let state = createMesiState(scenario);
  for (const op of ops) state = applyOp(state, op);
  return state;
}

export function mesiReducer(state, event) {
  switch (event.type) {
    case "CPU0_READ":
    case "CPU1_READ":
    case "CPU0_WRITE":
    case "CPU1_WRITE":
    case "CPU0_EVICT":
    case "CPU1_EVICT": {
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
      return createMesiState(state.scenario);
    case "SELECT_SCENARIO":
      return event.scenario === state.scenario ? state : createMesiState(event.scenario);
    default:
      return state;
  }
}

const OP_VERB = { READ: "read", WRITE: "wrote", EVICT: "evicted" };

// Short label for one history entry — used to render the visible event log
// (design.md "Visual model": the lab MUST show a textual event log).
export function mesiEventLabel(opType) {
  const actor = actorOf(opType);
  const verb = OP_VERB[opType.split("_")[1]];
  return `CPU ${actor} ${verb}.`;
}

function describeCpu(cpu) {
  return cpu.value == null ? cpu.state : `${cpu.state} (value ${cpu.value})`;
}

function describeOp(state, opType) {
  const actor = actorOf(opType);
  const verb = OP_VERB[opType.split("_")[1]];
  return `CPU ${actor} ${verb}. CPU 0: ${describeCpu(state.cpu0)}. CPU 1: ${describeCpu(state.cpu1)}. ` +
    `Invalidations: ${state.invalidations}. Transfers: ${state.transfers}. Write-backs: ${state.writeBacks}.`;
}

export function announceMesi(state, event) {
  switch (event.type) {
    case "CPU0_READ":
    case "CPU1_READ":
    case "CPU0_WRITE":
    case "CPU1_WRITE":
    case "CPU0_EVICT":
    case "CPU1_EVICT":
      return describeOp(state, event.type);
    case "NEXT_STEP":
    case "PREVIOUS_STEP": {
      const last = state.history[state.step - 1];
      return last
        ? `Step ${state.step} of ${state.history.length}. ${describeOp(state, last)}`
        : `Step 0 of ${state.history.length}. Initial state, both lines Invalid.`;
    }
    case "RESET":
      return "Reset. Both lines Invalid, no invalidations, transfers, or write-backs.";
    case "SELECT_SCENARIO":
      return `Scenario changed to ${scenarioLabel(state.scenario)}.`;
    default:
      return null;
  }
}
