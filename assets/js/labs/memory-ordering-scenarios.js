// Pure state/reducer for the memory-ordering lab's interactive model — no
// DOM access, so it's directly unit-testable (spec.md "Pure transitions").
// Contract: openspec/changes/plab-022-memory-ordering-java-rust/design.md.
//
// Models two threads executing a short, fixed, deterministic instruction
// script against a shared memory of a few named locations, with a per-thread
// FIFO store buffer for un-synchronized writes. This is a conceptual
// teaching model of ONE legal interleaving per scenario, not a simulation of
// every interleaving a real CPU could produce — see the .disclosure.conceptual
// block on the lab page and theory.md "Limitations of this model".

export const memoryOrderingScenarios = [
  { id: "broken-publication", label: "Broken publication" },
  { id: "release-acquire", label: "Release/acquire message passing" },
  { id: "relaxed-counter", label: "Relaxed counter" },
  { id: "store-buffering", label: "Store buffering" },
];

export const memoryOrderingEvents = ["NEXT_STEP", "PREVIOUS_STEP", "RESET", "SELECT_SCENARIO", "SELECT_ORDERING"];

// A "synchronizing" write (release/seqcst) flushes all of that thread's
// prior buffered writes before becoming visible itself, and its value is
// visible to an acquire/seqcst read on another thread with a happens-before
// edge — the textbook simplification this lab teaches (theory.md).
const SYNCHRONIZING_MODES = new Set(["release", "seqcst"]);
const ACQUIRING_MODES = new Set(["acquire", "seqcst"]);

// Each scenario's script is a fixed, ordered list of instructions — the ONE
// illustrative interleaving this lab demonstrates. `ordering` only varies
// the script for "store-buffering" (its whole teaching point); every other
// scenario has one fixed, inherent ordering.
function scriptFor(scenarioId, ordering) {
  switch (scenarioId) {
    case "broken-publication":
      return [
        { thread: 0, op: "WRITE", var: "data", mode: "plain", value: 1 },
        { thread: 0, op: "WRITE", var: "flag", mode: "plain", value: 1 },
        { thread: 0, op: "FLUSH", var: "flag" },
        { thread: 1, op: "READ", var: "flag", mode: "plain" },
        { thread: 1, op: "READ", var: "data", mode: "plain" },
        { thread: 0, op: "FLUSH", var: "data" },
      ];
    case "release-acquire":
      return [
        { thread: 0, op: "WRITE", var: "data", mode: "plain", value: 1 },
        { thread: 0, op: "WRITE", var: "flag", mode: "release", value: 1 },
        { thread: 1, op: "READ", var: "flag", mode: "acquire" },
        { thread: 1, op: "READ", var: "data", mode: "plain" },
      ];
    case "relaxed-counter":
      return [
        { thread: 0, op: "RMW", var: "counter", mode: "relaxed", delta: 1 },
        { thread: 1, op: "RMW", var: "counter", mode: "relaxed", delta: 1 },
        { thread: 0, op: "RMW", var: "counter", mode: "relaxed", delta: 1 },
        { thread: 1, op: "RMW", var: "counter", mode: "relaxed", delta: 1 },
      ];
    case "store-buffering":
      return ordering === "seqcst"
        ? [
            { thread: 0, op: "WRITE", var: "x", mode: "seqcst", value: 1 },
            { thread: 1, op: "WRITE", var: "y", mode: "seqcst", value: 1 },
            { thread: 0, op: "READ", var: "y", mode: "seqcst" },
            { thread: 1, op: "READ", var: "x", mode: "seqcst" },
          ]
        : [
            { thread: 0, op: "WRITE", var: "x", mode: "relaxed", value: 1 },
            { thread: 1, op: "WRITE", var: "y", mode: "relaxed", value: 1 },
            { thread: 0, op: "READ", var: "y", mode: "relaxed" },
            { thread: 1, op: "READ", var: "x", mode: "relaxed" },
            { thread: 0, op: "FLUSH", var: "x" },
            { thread: 1, op: "FLUSH", var: "y" },
          ];
    default:
      return [];
  }
}

// The ordering label shown in the state inspector — a fixed description for
// scenarios where it isn't user-selectable, or the live toggle value for
// store-buffering, the one scenario the ordering selector actually applies to.
function orderingLabel(scenarioId, ordering) {
  switch (scenarioId) {
    case "broken-publication": return "plain";
    case "release-acquire": return "release/acquire";
    case "relaxed-counter": return "relaxed";
    case "store-buffering": return ordering;
    default: return ordering;
  }
}

export function memoryOrderingScenarioMaxSteps(scenarioId, ordering) {
  return scriptFor(scenarioId, ordering).length;
}

function createThread() {
  return { pc: 0, buffer: [] };
}

export function createMemoryOrderingState(scenario = memoryOrderingScenarios[0].id, ordering = "relaxed") {
  return {
    scenario,
    ordering,
    step: 0,
    thread0: createThread(),
    thread1: createThread(),
    memory: {},
    memoryMeta: {},
    observations: [],
    edges: [],
    log: [],
  };
}

export const memoryOrderingInitialState = createMemoryOrderingState();

// Applies one scripted instruction to the running (mutable, replay-local)
// simulation state. Returns a short log entry describing what happened.
function applyInstruction(sim, instr, index) {
  const threads = [sim.thread0, sim.thread1];
  const thread = threads[instr.thread];

  if (instr.op === "WRITE") {
    if (SYNCHRONIZING_MODES.has(instr.mode)) {
      // A synchronizing write first flushes every write this thread has
      // buffered so far, in FIFO order, then becomes visible itself.
      const flushedCount = thread.buffer.length;
      for (const pending of thread.buffer) {
        sim.memory[pending.var] = pending.value;
        sim.memoryMeta[pending.var] = { mode: pending.mode, thread: instr.thread, step: pending.step };
      }
      thread.buffer = [];
      sim.memory[instr.var] = instr.value;
      sim.memoryMeta[instr.var] = { mode: instr.mode, thread: instr.thread, step: index };
      const flushNote = flushedCount > 0 ? ` — flushed ${flushedCount} pending buffered write${flushedCount > 1 ? "s" : ""} first` : "";
      return `T${instr.thread} wrote ${instr.var}=${instr.value} (${instr.mode})${flushNote}, published immediately.`;
    }
    thread.buffer.push({ var: instr.var, value: instr.value, mode: instr.mode, step: index });
    return `T${instr.thread} wrote ${instr.var}=${instr.value} (${instr.mode}) — buffered, not yet visible to the other thread.`;
  }

  if (instr.op === "FLUSH") {
    const bufIndex = thread.buffer.findIndex(entry => entry.var === instr.var);
    if (bufIndex === -1) return `T${instr.thread} flush of ${instr.var} — nothing pending.`;
    const [entry] = thread.buffer.splice(bufIndex, 1);
    sim.memory[entry.var] = entry.value;
    sim.memoryMeta[entry.var] = { mode: entry.mode, thread: instr.thread, step: entry.step };
    return `T${instr.thread}'s buffered write to ${instr.var}=${entry.value} becomes visible now.`;
  }

  if (instr.op === "RMW") {
    // Read-modify-write is always atomic and immediately visible, regardless
    // of ordering — this is the "atomicity without ordering" teaching point.
    const next = (sim.memory[instr.var] ?? 0) + instr.delta;
    sim.memory[instr.var] = next;
    sim.memoryMeta[instr.var] = { mode: instr.mode, thread: instr.thread, step: index };
    sim.observations.push({ step: index, thread: instr.thread, var: instr.var, value: next, mode: instr.mode });
    return `T${instr.thread} RMW ${instr.var} += ${instr.delta} (${instr.mode}) → ${next}.`;
  }

  // READ
  const value = sim.memory[instr.var] ?? 0;
  sim.observations.push({ step: index, thread: instr.thread, var: instr.var, value, mode: instr.mode });
  const meta = sim.memoryMeta[instr.var];
  if (ACQUIRING_MODES.has(instr.mode) && meta && SYNCHRONIZING_MODES.has(meta.mode) && meta.thread !== instr.thread) {
    sim.edges.push({ fromStep: meta.step, toStep: index });
    return `T${instr.thread} read ${instr.var} (${instr.mode}) → ${value}. Synchronizes-with the write at step ${meta.step + 1}.`;
  }
  return `T${instr.thread} read ${instr.var} (${instr.mode}) → ${value}.`;
}

// Replays scenario+ordering from scratch up to (not including) step
// `upToStep` — the same replay-from-history pattern as the other labs'
// scenario reducers, keeping PREVIOUS_STEP/NEXT_STEP exact without storing
// per-step snapshots.
function deriveState(scenarioId, ordering, upToStep) {
  const script = scriptFor(scenarioId, ordering);
  const sim = {
    thread0: createThread(),
    thread1: createThread(),
    memory: {},
    memoryMeta: {},
    observations: [],
    edges: [],
  };
  const log = [];
  for (let i = 0; i < upToStep; i++) {
    const text = applyInstruction(sim, script[i], i);
    log.push({ step: i + 1, thread: script[i].thread, text });
  }
  return {
    scenario: scenarioId,
    ordering,
    step: upToStep,
    thread0: sim.thread0,
    thread1: sim.thread1,
    memory: sim.memory,
    memoryMeta: sim.memoryMeta,
    observations: sim.observations,
    edges: sim.edges,
    log,
  };
}

export function memoryOrderingReducer(state, event) {
  switch (event.type) {
    case "NEXT_STEP": {
      const max = memoryOrderingScenarioMaxSteps(state.scenario, state.ordering);
      if (state.step >= max) return state;
      return deriveState(state.scenario, state.ordering, state.step + 1);
    }
    case "PREVIOUS_STEP":
      if (state.step <= 0) return state;
      return deriveState(state.scenario, state.ordering, state.step - 1);
    case "RESET":
      return createMemoryOrderingState(state.scenario, state.ordering);
    case "SELECT_SCENARIO":
      return event.scenario === state.scenario ? state : createMemoryOrderingState(event.scenario, state.ordering);
    case "SELECT_ORDERING": {
      if (state.scenario !== "store-buffering") return state;
      return event.ordering === state.ordering ? state : createMemoryOrderingState(state.scenario, event.ordering);
    }
    default:
      return state;
  }
}

// Short label for one log entry — used to render the visible event log.
export function memoryOrderingEventLabel(entry) {
  return `Step ${entry.step}: ${entry.text}`;
}

function scenarioLabel(id) {
  return memoryOrderingScenarios.find(s => s.id === id)?.label ?? id;
}

// The scenario-specific punchline once the script has fully run — computed
// from actual observed values, not hardcoded independent of state, so it
// stays honest if the mechanics above ever change.
export function memoryOrderingOutcome(state) {
  const max = memoryOrderingScenarioMaxSteps(state.scenario, state.ordering);
  if (state.step < max) return null;
  const obs = (thread, v) => state.observations.find(o => o.thread === thread && o.var === v)?.value;

  switch (state.scenario) {
    case "broken-publication": {
      const flag = obs(1, "flag");
      const data = obs(1, "data");
      return data === 0 && flag === 1
        ? `T1 observed flag=${flag} but data=${data} — a broken publication: seeing the "ready" signal did not guarantee seeing the data it was meant to guard.`
        : `T1 observed flag=${flag}, data=${data}.`;
    }
    case "release-acquire": {
      const flag = obs(1, "flag");
      const data = obs(1, "data");
      return `T1 observed flag=${flag} and therefore data=${data} — release/acquire guarantees this: once the acquire read observes the release write, every write before it in T0's program order is visible.`;
    }
    case "relaxed-counter":
      return `Final counter = ${state.memory.counter} — relaxed RMW is always atomic and always correct here, regardless of interleaving. Relaxed says nothing about ordering relative to any other variable.`;
    case "store-buffering": {
      const sawY = obs(0, "y");
      const sawX = obs(1, "x");
      return sawX === 0 && sawY === 0
        ? `Both threads observed 0 for the other's write (T0 saw y=${sawY}, T1 saw x=${sawX}) — the classic store-buffering outcome. Legal under relaxed ordering; forbidden under SeqCst.`
        : `T0 saw y=${sawY}, T1 saw x=${sawX} — under SeqCst, both observing 0 is forbidden; this run shows a SeqCst-consistent outcome instead.`;
    }
    default:
      return null;
  }
}

export function announceMemoryOrdering(state, event) {
  switch (event.type) {
    case "NEXT_STEP":
    case "PREVIOUS_STEP": {
      const last = state.log[state.log.length - 1];
      const outcome = memoryOrderingOutcome(state);
      if (!last) return "Step 0. No instructions executed yet.";
      return outcome ? `${memoryOrderingEventLabel(last)} ${outcome}` : memoryOrderingEventLabel(last);
    }
    case "RESET":
      return "Reset. No instructions executed, memory empty.";
    case "SELECT_SCENARIO":
      return `Scenario changed to ${scenarioLabel(state.scenario)}.`;
    case "SELECT_ORDERING":
      return `Ordering changed to ${state.ordering}.`;
    default:
      return null;
  }
}
