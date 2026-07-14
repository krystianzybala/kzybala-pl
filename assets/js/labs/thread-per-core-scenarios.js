// Pure state/reducer for the thread-per-core lab's interactive model — no
// DOM access, so it's directly unit-testable (spec.md "Pure transitions").
// Contract: openspec/changes/plab-025-thread-per-core/design.md.
//
// Models a fixed-size fleet of 4 cores processing requests under two
// architectures: a shared worker pool (any of N workers may handle any
// request, but all requests serialize on one shared-state lock) and
// thread-per-core ownership (each core owns a disjoint partition and
// processes its own bounded inbox in parallel with the others, with no
// lock at all). Each scenario is a fixed, hand-authored sequence of
// "arrival" turns (how many requests land, and on which core, in that
// time slice) plus, for one scenario, an injected scheduler-migration
// event — the same hand-scripted-turn-order approach as the SPSC ring
// buffer lab, since there is no scheduling nondeterminism worth
// simulating here either.

export const threadPerCoreScenarios = [
  { id: "worker-pool", label: "Shared worker pool" },
  { id: "owned-state", label: "Thread-per-core ownership" },
  { id: "cross-core-handoff", label: "Cross-core handoff" },
  { id: "hot-partition", label: "Hot partition" },
  { id: "scheduler-migration", label: "Scheduler migration" },
  { id: "backpressure", label: "Backpressure" },
];

export const threadPerCoreEvents = ["NEXT_STEP", "PREVIOUS_STEP", "RESET", "SELECT_SCENARIO"];

const CORE_COUNT = 4;

const SCENARIO_CONFIG = {
  "worker-pool": {
    mode: "worker-pool", queueCapacity: 6,
    plan: [
      { arrivals: [{}, {}, {}, {}] },
      { arrivals: [] },
      { arrivals: [] },
      { arrivals: [] },
    ],
  },
  "owned-state": {
    mode: "thread-per-core", queueCapacity: 3,
    plan: [
      { arrivals: [{ arrivalCore: 0, targetPartition: 0 }, { arrivalCore: 1, targetPartition: 1 }, { arrivalCore: 2, targetPartition: 2 }, { arrivalCore: 3, targetPartition: 3 }] },
    ],
  },
  "cross-core-handoff": {
    mode: "thread-per-core", queueCapacity: 3,
    plan: [
      { arrivals: [{ arrivalCore: 0, targetPartition: 1 }, { arrivalCore: 1, targetPartition: 2 }, { arrivalCore: 2, targetPartition: 3 }, { arrivalCore: 3, targetPartition: 0 }] },
      { arrivals: [] },
    ],
  },
  "hot-partition": {
    mode: "thread-per-core", queueCapacity: 3,
    plan: [
      { arrivals: [{ arrivalCore: 1, targetPartition: 1 }, { arrivalCore: 1, targetPartition: 1 }, { arrivalCore: 1, targetPartition: 1 }] },
      { arrivals: [{ arrivalCore: 1, targetPartition: 1 }, { arrivalCore: 1, targetPartition: 1 }, { arrivalCore: 1, targetPartition: 1 }] },
      { arrivals: [] },
      { arrivals: [] },
    ],
  },
  "scheduler-migration": {
    mode: "thread-per-core", queueCapacity: 3,
    plan: [
      { arrivals: [{ arrivalCore: 0, targetPartition: 0 }, { arrivalCore: 1, targetPartition: 1 }, { arrivalCore: 2, targetPartition: 2 }, { arrivalCore: 3, targetPartition: 3 }] },
      { migrate: { coreId: 2, toCpu: 5 } },
      { arrivals: [{ arrivalCore: 0, targetPartition: 0 }, { arrivalCore: 1, targetPartition: 1 }, { arrivalCore: 2, targetPartition: 2 }, { arrivalCore: 3, targetPartition: 3 }] },
    ],
  },
  "backpressure": {
    mode: "thread-per-core", queueCapacity: 3,
    plan: [
      { arrivals: [{ arrivalCore: 0, targetPartition: 0 }, { arrivalCore: 0, targetPartition: 0 }, { arrivalCore: 0, targetPartition: 0 }, { arrivalCore: 0, targetPartition: 0 }, { arrivalCore: 0, targetPartition: 0 }] },
      { arrivals: [] },
      { arrivals: [] },
    ],
  },
};

function scenarioLabel(id) {
  return threadPerCoreScenarios.find(s => s.id === id)?.label ?? id;
}

function createSim() {
  return {
    cores: Array.from({ length: CORE_COUNT }, (_, id) => ({ id, homeCpu: id, currentCpu: id, migrated: false, queue: [], processed: 0 })),
    sharedQueue: [],
    nextWorker: 0,
    totalRequests: 0,
    lockAcquisitions: 0,
    handoffs: 0,
    rejectedRequests: 0,
    migrationEvents: 0,
  };
}

export function createThreadPerCoreState(scenario = threadPerCoreScenarios[0].id) {
  const sim = createSim();
  return { scenario, step: 0, log: [], ...publicSnapshot(sim) };
}

export const threadPerCoreInitialState = createThreadPerCoreState();

function publicSnapshot(sim) {
  return {
    cores: sim.cores.map(c => ({ ...c, queue: c.queue.map(q => ({ ...q })) })),
    sharedQueue: sim.sharedQueue.map(q => ({ ...q })),
    nextWorker: sim.nextWorker,
    totalRequests: sim.totalRequests,
    lockAcquisitions: sim.lockAcquisitions,
    handoffs: sim.handoffs,
    rejectedRequests: sim.rejectedRequests,
    migrationEvents: sim.migrationEvents,
  };
}

function applyWorkerPoolTurn(sim, cfg, arrivals) {
  let accepted = 0;
  let rejected = 0;
  for (let i = 0; i < arrivals.length; i++) {
    sim.totalRequests++;
    if (sim.sharedQueue.length >= cfg.queueCapacity) {
      sim.rejectedRequests++;
      rejected++;
    } else {
      sim.sharedQueue.push({ id: sim.totalRequests });
      accepted++;
    }
  }

  let processedId = null;
  if (sim.sharedQueue.length > 0) {
    const item = sim.sharedQueue.shift();
    const worker = sim.nextWorker;
    sim.nextWorker = (sim.nextWorker + 1) % sim.cores.length;
    sim.lockAcquisitions++;
    sim.cores[worker].processed++;
    processedId = item.id;
  }

  const parts = [];
  if (accepted > 0) parts.push(`${accepted} request(s) arrive and join the single shared queue`);
  if (rejected > 0) parts.push(`${rejected} request(s) REJECTED — shared queue full, backpressure applied`);
  if (accepted === 0 && rejected === 0) parts.push("no new arrivals this turn");
  parts.push(processedId !== null
    ? `worker ${(sim.nextWorker + sim.cores.length - 1) % sim.cores.length} acquires the shared-state lock once to process request #${processedId}, then releases it`
    : "the shared queue is empty — no lock needed");
  return { kind: "worker-pool-turn", text: `${parts.join("; ")}. Shared queue depth now ${sim.sharedQueue.length}/${cfg.queueCapacity}.` };
}

function applyThreadPerCoreTurn(sim, cfg, arrivals) {
  const logParts = [];
  for (const spec of arrivals) {
    sim.totalRequests++;
    const target = sim.cores[spec.targetPartition];
    const isHandoff = spec.arrivalCore !== spec.targetPartition;
    if (target.queue.length >= cfg.queueCapacity) {
      sim.rejectedRequests++;
      logParts.push(`request for partition ${spec.targetPartition} (arrived on core ${spec.arrivalCore}) REJECTED — core ${spec.targetPartition}'s inbox is full (${target.queue.length}/${cfg.queueCapacity})`);
    } else {
      target.queue.push({ id: sim.totalRequests, eligible: !isHandoff });
      if (isHandoff) sim.handoffs++;
      logParts.push(isHandoff
        ? `request arrives on core ${spec.arrivalCore} but belongs to core ${spec.targetPartition}'s partition — handed off (inbox now ${target.queue.length}/${cfg.queueCapacity})`
        : `request arrives directly on core ${spec.targetPartition}, its own partition (inbox now ${target.queue.length}/${cfg.queueCapacity})`);
    }
  }

  const processedCores = [];
  for (const core of sim.cores) {
    if (core.queue.length > 0 && core.queue[0].eligible) {
      core.queue.shift();
      core.processed++;
      processedCores.push(core.id);
    }
  }
  // A handed-off item that just arrived becomes eligible starting next turn —
  // this is what gives a handoff exactly one turn of extra latency versus a
  // direct-dispatch item, which is eligible the instant it's enqueued.
  for (const core of sim.cores) {
    for (const item of core.queue) item.eligible = true;
  }

  const text = `${logParts.length > 0 ? `${logParts.join("; ")}. ` : "No new arrivals this turn. "}`
    + (processedCores.length > 1
      ? `Cores ${processedCores.join(", ")} each process one item from their own queue, in parallel.`
      : processedCores.length === 1
        ? `Core ${processedCores[0]} processes one item from its own queue.`
        : "No core has an eligible item to process yet.");

  return { kind: "request-turn", text };
}

function applyTurn(sim, cfg, index) {
  const token = cfg.plan[index];
  if (!token) return null;

  if (token.migrate) {
    const { coreId, toCpu } = token.migrate;
    const core = sim.cores[coreId];
    const fromCpu = core.currentCpu;
    core.currentCpu = toCpu;
    core.migrated = true;
    sim.migrationEvents++;
    return {
      step: index + 1,
      kind: "migrate",
      text: `Core ${coreId}'s OS thread is migrated from physical CPU ${fromCpu} to CPU ${toCpu} — logical ownership of partition ${coreId} is unchanged, but cache/NUMA locality built up on CPU ${fromCpu} is lost.`,
    };
  }

  const arrivals = token.arrivals || [];
  const result = cfg.mode === "worker-pool" ? applyWorkerPoolTurn(sim, cfg, arrivals) : applyThreadPerCoreTurn(sim, cfg, arrivals);
  return { step: index + 1, ...result };
}

export function threadPerCoreScenarioMaxSteps(scenarioId) {
  return SCENARIO_CONFIG[scenarioId].plan.length;
}

function deriveState(scenarioId, upToStep) {
  const cfg = SCENARIO_CONFIG[scenarioId];
  const sim = createSim();
  const log = [];
  for (let i = 0; i < upToStep; i++) {
    const entry = applyTurn(sim, cfg, i);
    if (!entry) break;
    log.push(entry);
  }
  return { scenario: scenarioId, step: upToStep, log, ...publicSnapshot(sim) };
}

export function threadPerCoreReducer(state, event) {
  switch (event.type) {
    case "NEXT_STEP": {
      const max = threadPerCoreScenarioMaxSteps(state.scenario);
      if (state.step >= max) return state;
      return deriveState(state.scenario, state.step + 1);
    }
    case "PREVIOUS_STEP":
      if (state.step <= 0) return state;
      return deriveState(state.scenario, state.step - 1);
    case "RESET":
      return createThreadPerCoreState(state.scenario);
    case "SELECT_SCENARIO":
      return event.scenario === state.scenario ? state : createThreadPerCoreState(event.scenario);
    default:
      return state;
  }
}

export function threadPerCoreEventLabel(entry) {
  return `Step ${entry.step}: ${entry.text}`;
}

export function announceThreadPerCore(state, event) {
  switch (event.type) {
    case "NEXT_STEP":
    case "PREVIOUS_STEP": {
      const last = state.log[state.log.length - 1];
      return last
        ? `${threadPerCoreEventLabel(last)} Handoffs: ${state.handoffs}. Rejected: ${state.rejectedRequests}. Migrations: ${state.migrationEvents}.`
        : "Step 0. No requests have arrived yet.";
    }
    case "RESET":
      return "Reset. No requests, no handoffs, no migrations.";
    case "SELECT_SCENARIO":
      return `Scenario changed to ${scenarioLabel(state.scenario)}.`;
    default:
      return null;
  }
}
