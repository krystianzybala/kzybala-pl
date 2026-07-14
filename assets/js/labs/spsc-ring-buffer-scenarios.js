// Pure state/reducer for the SPSC ring-buffer lab's interactive model — no
// DOM access, so it's directly unit-testable (spec.md "Pure transitions").
// Contract: openspec/changes/plab-024-spsc-ring-buffer/design.md.
//
// Models a bounded single-producer/single-consumer ring buffer with cursors
// kept deliberately separate per design.md: reservation (reserveIndex) is
// distinct from publication (head), and payload read (readIndex) is
// distinct from consumption acknowledgement (tail). Each scenario is a
// fixed, hand-authored turn order ("P" = producer acts next, "C" = consumer
// acts next) — unlike cas-contention's emergent contention scheduling, an
// SPSC buffer has exactly one producer and one consumer, so there is no
// scheduling nondeterminism to simulate; only the phase each actor is
// currently in determines what a given turn does.

export const spscRingBufferScenarios = [
  { id: "normal", label: "Normal flow" },
  { id: "wrap-around", label: "Wrap-around" },
  { id: "full", label: "Full buffer" },
  { id: "empty", label: "Empty buffer" },
  { id: "cached-cursor", label: "Cached cursor" },
  { id: "batch", label: "Batch publication" },
  { id: "bug-ordering", label: "Bug: publish before write" },
  { id: "bug-overwrite", label: "Bug: overwrite unconsumed" },
];

export const spscRingBufferEvents = ["NEXT_STEP", "PREVIOUS_STEP", "RESET", "SELECT_SCENARIO"];

const SCENARIO_CONFIG = {
  "normal": {
    capacity: 4, mode: "correct", batchSize: 1,
    plan: ["P", "P", "P", "C", "C", "P", "P", "P", "C", "C"],
  },
  "wrap-around": {
    capacity: 2, mode: "correct", batchSize: 1,
    plan: ["P", "P", "P", "P", "P", "P", "C", "C", "C", "C", "P", "P", "P"],
  },
  "full": {
    capacity: 2, mode: "correct", batchSize: 1,
    plan: ["P", "P", "P", "P", "P", "P", "P"],
  },
  "empty": {
    capacity: 2, mode: "correct", batchSize: 1,
    plan: ["C", "P", "P", "P", "C", "C"],
  },
  "cached-cursor": {
    capacity: 4, mode: "correct", batchSize: 1,
    plan: ["P", "P", "P", "P", "P", "P", "P", "P", "P", "P", "P", "P", "C", "C", "C", "C", "P", "P", "P"],
  },
  "batch": {
    capacity: 4, mode: "correct", batchSize: 3,
    plan: ["P", "P", "P", "P", "P", "C", "C", "C", "C"],
  },
  "bug-ordering": {
    capacity: 2, mode: "bug-ordering", batchSize: 1,
    staleSeed: { index: 0, value: 999 },
    plan: ["P", "P", "C", "C", "P"],
  },
  "bug-overwrite": {
    capacity: 2, mode: "bug-overwrite", batchSize: 1,
    plan: ["P", "P", "P", "P", "P", "P", "P", "P", "P"],
  },
};

function scenarioLabel(id) {
  return spscRingBufferScenarios.find(s => s.id === id)?.label ?? id;
}

function describeIndices(start, count, cap) {
  if (count <= 1) return String(start % cap);
  const indices = Array.from({ length: count }, (_, i) => (start + i) % cap);
  return indices.join(", ");
}

function createSim(scenarioId) {
  const cfg = SCENARIO_CONFIG[scenarioId];
  const slots = Array.from({ length: cfg.capacity }, () => ({ value: null, published: false }));
  if (cfg.staleSeed) {
    slots[cfg.staleSeed.index] = { value: cfg.staleSeed.value, published: false, stale: true };
  }
  return {
    reserveIndex: 0, head: 0, readIndex: 0, tail: 0,
    producerCachedTail: 0, consumerCachedHead: 0,
    slots,
    producerReserved: false, producerPendingCount: 0, producerWrittenCount: 0, producerPublishedEarly: false,
    consumerHeldCount: 0,
    nextValue: 0,
    producerCacheHits: 0, producerCacheRefreshes: 0,
    consumerCacheHits: 0, consumerCacheRefreshes: 0,
    rejectedReservations: 0, starvedReads: 0,
    overwrites: 0, incorrectReads: 0,
    batchPublishes: 0, batchAcks: 0, singlePublishes: 0, singleAcks: 0,
  };
}

export function createSpscRingBufferState(scenario = spscRingBufferScenarios[0].id) {
  const sim = createSim(scenario);
  return { scenario, step: 0, log: [], ...publicSnapshot(sim) };
}

export const spscRingBufferInitialState = createSpscRingBufferState();

function publicSnapshot(sim) {
  return {
    reserveIndex: sim.reserveIndex, head: sim.head, readIndex: sim.readIndex, tail: sim.tail,
    producerCachedTail: sim.producerCachedTail, consumerCachedHead: sim.consumerCachedHead,
    slots: sim.slots.map(s => ({ ...s })),
    producerReserved: sim.producerReserved, producerPendingCount: sim.producerPendingCount,
    producerWrittenCount: sim.producerWrittenCount, producerPublishedEarly: sim.producerPublishedEarly,
    consumerHeldCount: sim.consumerHeldCount,
    producerCacheHits: sim.producerCacheHits, producerCacheRefreshes: sim.producerCacheRefreshes,
    consumerCacheHits: sim.consumerCacheHits, consumerCacheRefreshes: sim.consumerCacheRefreshes,
    rejectedReservations: sim.rejectedReservations, starvedReads: sim.starvedReads,
    overwrites: sim.overwrites, incorrectReads: sim.incorrectReads,
    batchPublishes: sim.batchPublishes, batchAcks: sim.batchAcks,
    singlePublishes: sim.singlePublishes, singleAcks: sim.singleAcks,
  };
}

function applyProducerTurn(sim, cfg) {
  const cap = cfg.capacity;
  const batch = cfg.batchSize;
  const buggyOverwrite = cfg.mode === "bug-overwrite";
  const buggyOrdering = cfg.mode === "bug-ordering";

  if (!sim.producerReserved) {
    let available = cap - (sim.reserveIndex - sim.producerCachedTail);
    let usedRefresh = false;
    if (!buggyOverwrite && available < batch) {
      sim.producerCachedTail = sim.tail;
      usedRefresh = true;
      available = cap - (sim.reserveIndex - sim.producerCachedTail);
    }
    if (!buggyOverwrite && available < batch) {
      sim.rejectedReservations++;
      return { kind: "reject", text: `Producer reservation REJECTED — buffer full (${sim.reserveIndex - sim.tail}/${cap} slots claimed, none freed yet).` };
    }
    if (usedRefresh) sim.producerCacheRefreshes++; else sim.producerCacheHits++;
    const startIndex = sim.reserveIndex;
    sim.reserveIndex += batch;
    sim.producerReserved = true;
    sim.producerPendingCount = batch;
    sim.producerWrittenCount = 0;
    sim.producerPublishedEarly = false;
    const checkSuffix = buggyOverwrite
      ? " (BUG: capacity check skipped entirely — the producer never looks at the consumer's tail)"
      : usedRefresh ? " (cache said full — refreshed from the consumer's real tail)" : " (cache hit — no need to read the real tail)";
    return {
      kind: "reserve",
      text: `Producer reserves slot${batch > 1 ? "s" : ""} ${describeIndices(startIndex, batch, cap)}${checkSuffix}.`,
    };
  }

  if (buggyOrdering && !sim.producerPublishedEarly) {
    const slotIdx = (sim.reserveIndex - sim.producerPendingCount) % cap;
    sim.head = sim.reserveIndex;
    sim.slots[slotIdx].published = true;
    sim.producerPublishedEarly = true;
    return {
      kind: "publish-early-bug",
      text: `BUG: producer publishes slot ${slotIdx} before writing its payload — head advances to ${sim.head}, exposing whatever was already in the slot.`,
    };
  }

  if (sim.producerWrittenCount < sim.producerPendingCount) {
    const slotIdx = (sim.reserveIndex - sim.producerPendingCount + sim.producerWrittenCount) % cap;
    const value = sim.nextValue++;
    const old = sim.slots[slotIdx];
    const overwritingUnconsumed = old.published === true;
    if (overwritingUnconsumed) sim.overwrites++;
    sim.slots[slotIdx] = { value, published: buggyOrdering ? sim.slots[slotIdx].published : false };
    sim.producerWrittenCount++;
    if (buggyOrdering) sim.producerReserved = false; // bug path: publish already happened, nothing left to do after the late write
    const lateSuffix = buggyOrdering ? " — too late: this slot was already published and may already have been consumed." : "";
    return {
      kind: "write",
      text: overwritingUnconsumed
        ? `Producer writes payload ${value} into slot ${slotIdx} — OVERWRITE BUG: slot ${slotIdx} still held unconsumed value ${old.value}, which is now permanently lost.`
        : `Producer writes payload ${value} into slot ${slotIdx} (not yet visible to the consumer).${lateSuffix}`,
      overwrite: overwritingUnconsumed,
    };
  }

  // PUBLISH (correct/bug-overwrite path only — bug-ordering already published early)
  const n = sim.producerPendingCount;
  const startIndex = sim.reserveIndex - n;
  sim.head = sim.reserveIndex;
  for (let i = 0; i < n; i++) sim.slots[(startIndex + i) % cap].published = true;
  if (n > 1) sim.batchPublishes++; else sim.singlePublishes++;
  sim.producerReserved = false;
  return {
    kind: "publish",
    text: `Producer publishes ${n > 1 ? `${n} slots at once (1 release-store of head instead of ${n})` : "the slot"} — now visible to the consumer.`,
  };
}

function applyConsumerTurn(sim, cfg) {
  const cap = cfg.capacity;
  const batch = cfg.batchSize;

  if (sim.consumerHeldCount >= batch && sim.consumerHeldCount > 0) {
    const n = sim.consumerHeldCount;
    const startIndex = sim.readIndex - n;
    sim.tail = sim.readIndex;
    for (let i = 0; i < n; i++) sim.slots[(startIndex + i) % cap].published = false;
    if (n > 1) sim.batchAcks++; else sim.singleAcks++;
    sim.consumerHeldCount = 0;
    return {
      kind: "ack",
      text: `Consumer acknowledges ${n > 1 ? `${n} slots at once (1 tail update instead of ${n})` : "consumption"} — slot${n > 1 ? "s" : ""} now free for reuse.`,
    };
  }

  let available = sim.consumerCachedHead - sim.readIndex;
  let usedRefresh = false;
  if (available <= 0) {
    sim.consumerCachedHead = sim.head;
    usedRefresh = true;
    available = sim.consumerCachedHead - sim.readIndex;
  }
  if (available <= 0) {
    sim.starvedReads++;
    return { kind: "starved", text: "Consumer READ finds the buffer empty — nothing published yet to consume." };
  }
  if (usedRefresh) sim.consumerCacheRefreshes++; else sim.consumerCacheHits++;
  const slotIdx = sim.readIndex % cap;
  const slot = sim.slots[slotIdx];
  sim.readIndex++;
  sim.consumerHeldCount++;
  const incorrect = cfg.mode === "bug-ordering" && slot.stale === true;
  if (incorrect) sim.incorrectReads++;
  return {
    kind: "read",
    text: `Consumer reads slot ${slotIdx} = ${slot.value}${usedRefresh ? " (cache refreshed from the producer's real head)" : " (cache hit)"}${incorrect ? " — BUG: this is stale leftover data, the real payload has not been written yet!" : ""}.`,
    incorrect,
  };
}

function applyTurn(sim, cfg, index) {
  const token = cfg.plan[index];
  if (token === undefined) return null;
  const entry = token === "P" ? applyProducerTurn(sim, cfg) : applyConsumerTurn(sim, cfg);
  return { step: index + 1, actor: token === "P" ? "producer" : "consumer", ...entry };
}

export function spscRingBufferScenarioMaxSteps(scenarioId) {
  return SCENARIO_CONFIG[scenarioId].plan.length;
}

function deriveState(scenarioId, upToStep) {
  const cfg = SCENARIO_CONFIG[scenarioId];
  const sim = createSim(scenarioId);
  const log = [];
  for (let i = 0; i < upToStep; i++) {
    const entry = applyTurn(sim, cfg, i);
    if (!entry) break;
    log.push(entry);
  }
  return { scenario: scenarioId, step: upToStep, log, ...publicSnapshot(sim) };
}

export function spscRingBufferReducer(state, event) {
  switch (event.type) {
    case "NEXT_STEP": {
      const max = spscRingBufferScenarioMaxSteps(state.scenario);
      if (state.step >= max) return state;
      return deriveState(state.scenario, state.step + 1);
    }
    case "PREVIOUS_STEP":
      if (state.step <= 0) return state;
      return deriveState(state.scenario, state.step - 1);
    case "RESET":
      return createSpscRingBufferState(state.scenario);
    case "SELECT_SCENARIO":
      return event.scenario === state.scenario ? state : createSpscRingBufferState(event.scenario);
    default:
      return state;
  }
}

export function spscRingBufferEventLabel(entry) {
  return `Step ${entry.step}: ${entry.text}`;
}

export function announceSpscRingBuffer(state, event) {
  switch (event.type) {
    case "NEXT_STEP":
    case "PREVIOUS_STEP": {
      const last = state.log[state.log.length - 1];
      return last
        ? `${spscRingBufferEventLabel(last)} Occupancy: ${state.head - state.tail}. Rejected: ${state.rejectedReservations}. Starved: ${state.starvedReads}.`
        : "Step 0. Buffer empty, nothing produced or consumed yet.";
    }
    case "RESET":
      return "Reset. Buffer empty, all cursors at zero.";
    case "SELECT_SCENARIO":
      return `Scenario changed to ${scenarioLabel(state.scenario)}.`;
    default:
      return null;
  }
}
