// Pure state/reducer for the cache-hierarchy lab's interactive model — no
// DOM access, so it's directly unit-testable (spec.md "Pure transitions").
// Contract: openspec/changes/plab-020-cache-hierarchy/design.md.
//
// Models a simplified 3-level inclusive-ish cache (L1/L2/L3, LRU eviction
// cascading down through the levels) plus a single-stride hardware
// prefetcher for sequential access — deliberately simplified vs. real
// hardware and explicitly conceptual, see the .disclosure.conceptual block
// on the lab page and docs/components.md.

export const L1_CAPACITY = 4;
export const L2_CAPACITY = 8;
export const L3_CAPACITY = 16;
export const MAX_STEPS = 24;

export const cacheHierarchyScenarios = [
  { id: "sequential-small", label: "Sequential — fits in L1" },
  { id: "sequential-large", label: "Sequential — exceeds cache" },
  { id: "random-small", label: "Random — fits in L1" },
  { id: "random-large", label: "Random — exceeds cache" },
];

const SCENARIO_CONFIG = {
  "sequential-small": { n: 4, pattern: "sequential" },
  "sequential-large": { n: 32, pattern: "sequential" },
  "random-small": { n: 4, pattern: "random" },
  "random-large": { n: 32, pattern: "random" },
};

// A hand-picked order visiting all 4 lines exactly once per period, distinct
// from the sequential 0,1,2,3 order.
const RANDOM_SMALL_ORDER = [2, 0, 3, 1];

// A 5-bit-reversal permutation of 0..31 — deterministic, dependency-free,
// and visits every line exactly once per 32-step period with no detectable
// stride, standing in for "random" without needing Math.random (keeps this
// module pure and reproducible in tests).
function bitReverse5(i) {
  let r = 0;
  for (let b = 0; b < 5; b++) r |= ((i >> b) & 1) << (4 - b);
  return r;
}
const RANDOM_LARGE_ORDER = Array.from({ length: 32 }, (_, i) => bitReverse5(i));

// Number of distinct lines in a scenario's working set — used by the
// bootstrap module to size the working-set grid it renders.
export function cacheHierarchyScenarioSize(scenarioId) {
  return SCENARIO_CONFIG[scenarioId].n;
}

function lineAt(scenarioId, step) {
  const { n, pattern } = SCENARIO_CONFIG[scenarioId];
  if (pattern === "sequential") return step % n;
  const order = n === 4 ? RANDOM_SMALL_ORDER : RANDOM_LARGE_ORDER;
  return order[step % order.length];
}

function createLevels() {
  return { l1: [], l2: [], l3: [] };
}

function removeFrom(list, line) {
  const idx = list.indexOf(line);
  if (idx !== -1) list.splice(idx, 1);
}

// Inserts `line` at the MRU position (front) of `list`; if that overflows
// `cap`, evicts and returns the LRU (last) entry, else returns null.
function insertMru(list, line, cap) {
  list.unshift(line);
  if (list.length > cap) return list.pop();
  return null;
}

// Places `line` into L1, cascading any eviction down through L2 and L3.
// A line evicted from L3 is simply discarded (falls out of the hierarchy,
// back to "resident nowhere" — the next access to it will be a RAM miss).
function placeInL1(levels, line) {
  const evicted1 = insertMru(levels.l1, line, L1_CAPACITY);
  if (evicted1 == null) return;
  const evicted2 = insertMru(levels.l2, evicted1, L2_CAPACITY);
  if (evicted2 == null) return;
  insertMru(levels.l3, evicted2, L3_CAPACITY);
}

// Places `line` directly into L2 — used for prefetch, which real hardware
// warms into a lower cache level rather than L1 to avoid polluting it.
function placeInL2(levels, line) {
  const evicted2 = insertMru(levels.l2, line, L2_CAPACITY);
  if (evicted2 == null) return;
  insertMru(levels.l3, evicted2, L3_CAPACITY);
}

function residentLevel(levels, line) {
  if (levels.l1.includes(line)) return "l1";
  if (levels.l2.includes(line)) return "l2";
  if (levels.l3.includes(line)) return "l3";
  return "ram";
}

// Applies one access to `line`, mutating `levels` in place, and returns the
// level it was found at *before* promotion, plus the prefetched line (if
// any). Sequential scenarios simulate a one-line-ahead stride prefetcher:
// after touching `line`, the next line in stride order is warmed into L2
// if it isn't already cached somewhere.
function applyAccess(levels, scenarioId, step) {
  const { pattern, n } = SCENARIO_CONFIG[scenarioId];
  const line = lineAt(scenarioId, step);
  const level = residentLevel(levels, line);

  if (level === "l1") {
    removeFrom(levels.l1, line);
    levels.l1.unshift(line);
  } else if (level === "l2") {
    removeFrom(levels.l2, line);
    placeInL1(levels, line);
  } else if (level === "l3") {
    removeFrom(levels.l3, line);
    placeInL1(levels, line);
  } else {
    placeInL1(levels, line);
  }

  let prefetched = null;
  if (pattern === "sequential") {
    const next = (line + 1) % n;
    if (residentLevel(levels, next) === "ram") {
      placeInL2(levels, next);
      prefetched = next;
    }
  }

  return { line, level, prefetched };
}

export function createCacheHierarchyState(scenario = cacheHierarchyScenarios[0].id) {
  return {
    scenario,
    step: 0,
    levels: createLevels(),
    log: [],
    counts: { l1: 0, l2: 0, l3: 0, ram: 0 },
  };
}

export const cacheHierarchyInitialState = createCacheHierarchyState();

// Replays scenario `scenarioId` from scratch up to (not including) step
// `upToStep`, deriving the resulting state deterministically — the same
// replay-from-history pattern as false-sharing-scenarios.js, which keeps
// PREVIOUS_STEP/NEXT_STEP exact without needing to store per-step snapshots.
function deriveState(scenarioId, upToStep) {
  const levels = createLevels();
  const log = [];
  const counts = { l1: 0, l2: 0, l3: 0, ram: 0 };
  for (let step = 0; step < upToStep; step++) {
    const { line, level, prefetched } = applyAccess(levels, scenarioId, step);
    counts[level]++;
    log.push({ step: step + 1, line, level, prefetched });
  }
  return { scenario: scenarioId, step: upToStep, levels, log, counts };
}

export function cacheHierarchyReducer(state, event) {
  switch (event.type) {
    case "NEXT_STEP":
      return state.step >= MAX_STEPS ? state : deriveState(state.scenario, state.step + 1);
    case "PREVIOUS_STEP":
      return state.step <= 0 ? state : deriveState(state.scenario, state.step - 1);
    case "RESET":
      return createCacheHierarchyState(state.scenario);
    case "SELECT_SCENARIO":
      return event.scenario === state.scenario ? state : createCacheHierarchyState(event.scenario);
    default:
      return state;
  }
}

const LEVEL_LABEL = { l1: "L1 hit", l2: "L2 hit", l3: "L3 hit", ram: "RAM miss" };

// Short label for one log entry — used to render the visible event log
// (design.md "Visual rules": every transition updates an event log).
export function cacheHierarchyEventLabel(entry) {
  const prefetchNote = entry.prefetched != null ? `; prefetched line ${entry.prefetched} into L2` : "";
  return `Step ${entry.step}: accessed line ${entry.line} — ${LEVEL_LABEL[entry.level]}${prefetchNote}.`;
}

function scenarioLabel(id) {
  return cacheHierarchyScenarios.find(s => s.id === id)?.label ?? id;
}

export function announceCacheHierarchy(state, event) {
  switch (event.type) {
    case "NEXT_STEP":
    case "PREVIOUS_STEP": {
      const last = state.log[state.log.length - 1];
      return last
        ? cacheHierarchyEventLabel(last)
        : "Step 0. No accesses yet.";
    }
    case "RESET":
      return "Reset. Cache hierarchy empty, no accesses yet.";
    case "SELECT_SCENARIO":
      return `Scenario changed to ${scenarioLabel(state.scenario)}.`;
    default:
      return null;
  }
}
