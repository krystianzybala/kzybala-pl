// Bootstrap for /lab/cache-hierarchy/ — mounts the interactive working-set
// grid, the Java/Rust code tabs and the review quiz onto the static page.
// Contract: docs/lab-framework.md "Progressive enhancement bootstrap".
import { createLabDefinition, mountLab } from "../core/lab-framework.js";
import { renderScenarioSelector, renderStepControls, renderStateInspector, mountCodeTabs, renderQuiz } from "../core/components.js";
import {
  cacheHierarchyInitialState,
  cacheHierarchyReducer,
  cacheHierarchyScenarios,
  cacheHierarchyScenarioSize,
  cacheHierarchyEventLabel,
  announceCacheHierarchy,
  MAX_STEPS,
} from "./cache-hierarchy-scenarios.js";

const SCENARIO_COPY = {
  "sequential-small": "A 4-line working set, walked in order, wrapping around. Small enough to fit in L1 on its own — watch it settle into all-L1-hit after the first pass.",
  "sequential-large": "A 32-line working set (standing in for a working set far larger than any real cache), walked in order. Each miss also warms the next line in L2 — a simulated hardware prefetcher — so most steps land in L2 despite the working set exceeding total cache capacity.",
  "random-small": "The same 4-line working set as “Sequential — fits in L1”, visited in a fixed non-sequential order. No prefetch triggers, but once every line has been touched once it still settles into all-L1-hit — order stops mattering once the whole set fits.",
  "random-large": "The same 32-line working set as “Sequential — exceeds cache”, visited in a fixed non-sequential order with no detectable stride. No prefetch ever triggers, and the working set is larger than L1+L2+L3 combined — expect RAM misses on almost every step.",
};

const LEVEL_LABEL = { l1: "L1 hit", l2: "L2 hit", l3: "L3 hit", ram: "RAM miss" };

function renderGrid(root, state) {
  const grid = root.querySelector("[data-lab-grid]");
  if (!grid) return;
  const n = cacheHierarchyScenarioSize(state.scenario);
  const currentLine = state.log[state.log.length - 1]?.line ?? null;

  grid.className = `cache-line${n <= 4 ? " line-4" : ""}`;
  grid.replaceChildren(...Array.from({ length: n }, (_, line) => {
    const cell = document.createElement("span");
    let level = "ram";
    if (state.levels.l1.includes(line)) level = "l1";
    else if (state.levels.l2.includes(line)) level = "l2";
    else if (state.levels.l3.includes(line)) level = "l3";
    cell.className = `cache-cell${level === "ram" ? "" : ` level-${level}`}${line === currentLine ? " current" : ""}`;
    cell.title = `Line ${line}: ${LEVEL_LABEL[level]}`;
    return cell;
  }));
}

const stage = document.querySelector("[data-lab-stage]");

mountLab(stage, createLabDefinition({
  metadata: { id: "cache-hierarchy" },
  initialState: cacheHierarchyInitialState,
  events: ["NEXT_STEP", "PREVIOUS_STEP", "RESET", "SELECT_SCENARIO"],
  scenarios: cacheHierarchyScenarios,
  reducer: cacheHierarchyReducer,
  announce: announceCacheHierarchy,
  render(state, { dispatch, root }) {
    const nav = root.querySelector("[data-lab-scenario-nav]");
    renderScenarioSelector(nav, {
      scenarios: cacheHierarchyScenarios,
      current: state.scenario,
      onSelect: scenario => dispatch({ type: "SELECT_SCENARIO", scenario }),
    });

    const description = root.querySelector("[data-scenario-description]");
    if (description) description.textContent = SCENARIO_COPY[state.scenario];

    renderGrid(root, state);

    const last = state.log[state.log.length - 1];
    const result = root.querySelector("[data-lab-result]");
    if (result) {
      result.textContent = last ? `Line ${last.line}: ${LEVEL_LABEL[last.level]}${last.prefetched != null ? ` (prefetched line ${last.prefetched} into L2)` : ""}` : "No accesses yet.";
      result.className = `state-badge ${last ? { l1: "is-success", l2: "is-active", l3: "is-warning", ram: "is-danger" }[last.level] : "is-idle"}`;
    }

    const eventLog = root.querySelector("[data-lab-event-log]");
    if (eventLog) {
      eventLog.replaceChildren(...["Initial state — cache hierarchy empty.", ...state.log.map(cacheHierarchyEventLabel)].map(text => {
        const li = document.createElement("li");
        li.textContent = text;
        return li;
      }));
    }

    renderStateInspector(root.querySelector("[data-lab-inspector]"), [
      { label: "Scenario", value: cacheHierarchyScenarios.find(s => s.id === state.scenario)?.label ?? state.scenario },
      { label: "Step", value: `${state.step} of ${MAX_STEPS}` },
      { label: "L1 hits", value: state.counts.l1 },
      { label: "L2 hits", value: state.counts.l2 },
      { label: "L3 hits", value: state.counts.l3 },
      { label: "RAM misses", value: state.counts.ram },
    ]);

    renderStepControls(root.querySelector("[data-lab-controls]"), {
      onPrevious: () => dispatch({ type: "PREVIOUS_STEP" }),
      onNext: () => dispatch({ type: "NEXT_STEP" }),
      onReset: () => dispatch({ type: "RESET" }),
      canGoBack: state.step > 0,
      canGoForward: state.step < MAX_STEPS,
    });
  },
}));

mountCodeTabs(document.querySelector("[data-lab-code-tabs]"));

renderQuiz(document.querySelector("[data-lab-quiz]"), {
  question: "A loop reads 128 MB of data — far larger than any cache level — in sequential index order, one element after another. Why is it typically much faster than reading the same 128 MB in a random order, even though both read the exact same number of bytes?",
  choices: [
    "Sequential reads use less memory bandwidth than random reads",
    "The hardware prefetcher detects the stride and fetches ahead, hiding most of the RAM latency; random access has no stride to detect",
    "The CPU compresses sequential data automatically",
    "Random access always triggers a data race",
  ],
  correctIndex: 1,
});
