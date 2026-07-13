// Bootstrap for /lab/mesi/ — mounts the interactive MESI state-transition
// model, the Java/Rust code tabs and the review quiz onto the static page.
// Contract: docs/lab-framework.md "Progressive enhancement bootstrap".
import { createLabDefinition, mountLab } from "../core/lab-framework.js";
import { renderScenarioSelector, renderStepControls, renderStateInspector, mountCodeTabs, renderQuiz } from "../core/components.js";
import {
  mesiInitialState,
  mesiReducer,
  mesiScenarios,
  mesiEventLabel,
  announceMesi,
} from "./mesi-scenarios.js";

const SCENARIO_COPY = {
  "single-reader": "CPU 0 reads. No other cache holds the line, so it fetches from memory and takes it Exclusive — a clean copy nobody else needs to be told about. Try CPU 0: read.",
  "two-readers": "CPU 0 reads (Exclusive), then CPU 1 reads. A second reader forces a cache-to-cache transfer and downgrades both copies to Shared. Try CPU 0: read, then CPU 1: read.",
  "reader-then-writer": "CPU 0 reads, then CPU 1 writes. CPU 1's write invalidates CPU 0's copy and claims sole Modified ownership. Try CPU 0: read, then CPU 1: write.",
  "competing-writers": "CPU 0 writes (Modified), then CPU 1 writes. CPU 1's write forces a write-back and cache-to-cache transfer of CPU 0's dirty value before invalidating it. Try CPU 0: write, then CPU 1: write.",
  "eviction-writeback": "CPU 0 writes (Modified), then evicts. Evicting a Modified line forces a write-back to memory before the line goes Invalid. Try CPU 0: write, then CPU 0: evict.",
};

const STATE_BADGE = { Modified: "is-active", Exclusive: "is-success", Shared: "is-idle", Invalid: "is-danger" };

function renderCpu(root, index, cpuState) {
  const cpu = root.querySelector(`.cpu[data-cpu="${index}"]`);
  if (!cpu) return;
  const badge = cpu.querySelector("[data-cpu-badge]");
  if (badge) {
    badge.textContent = cpuState.state;
    badge.className = `state-badge ${STATE_BADGE[cpuState.state]}`;
  }
  const value = cpu.querySelector("[data-cpu-value]");
  if (value) value.textContent = `Value: ${cpuState.value == null ? "—" : cpuState.value}`;
  const cell = cpu.querySelector("[data-cpu-cell]");
  if (cell) cell.classList.toggle("hot", cpuState.state === "Modified");
}

const stage = document.querySelector("[data-lab-stage]");

const lab = mountLab(stage, createLabDefinition({
  metadata: { id: "mesi" },
  initialState: mesiInitialState,
  events: [
    "CPU0_READ", "CPU1_READ", "CPU0_WRITE", "CPU1_WRITE", "CPU0_EVICT", "CPU1_EVICT",
    "NEXT_STEP", "PREVIOUS_STEP", "RESET", "SELECT_SCENARIO",
  ],
  scenarios: mesiScenarios,
  reducer: mesiReducer,
  announce: announceMesi,
  render(state, { dispatch, root }) {
    const nav = root.querySelector("[data-lab-scenario-nav]");
    renderScenarioSelector(nav, {
      scenarios: mesiScenarios,
      current: state.scenario,
      onSelect: scenario => dispatch({ type: "SELECT_SCENARIO", scenario }),
    });

    const description = root.querySelector("[data-scenario-description]");
    if (description) description.textContent = SCENARIO_COPY[state.scenario];

    renderCpu(root, 0, state.cpu0);
    renderCpu(root, 1, state.cpu1);

    const bus = root.querySelector("[data-coherence-bus]");
    if (bus) {
      bus.classList.remove("pulse");
      void bus.offsetWidth;
      bus.classList.add("pulse");
    }

    const eventLog = root.querySelector("[data-lab-event-log]");
    if (eventLog) {
      const entries = state.history.slice(0, state.step).map(mesiEventLabel);
      eventLog.replaceChildren(...["Initial state — both lines Invalid.", ...entries].map(text => {
        const li = document.createElement("li");
        li.textContent = text;
        return li;
      }));
    }

    renderStateInspector(root.querySelector("[data-lab-inspector]"), [
      { label: "Scenario", value: mesiScenarios.find(s => s.id === state.scenario)?.label ?? state.scenario },
      { label: "Step", value: `${state.step} of ${state.history.length}` },
      { label: "Memory value", value: state.memoryValue },
      { label: "Owner", value: state.owner === null ? "none" : `CPU ${state.owner}` },
      { label: "Invalidations", value: state.invalidations },
      { label: "Transfers", value: state.transfers },
      { label: "Write-backs", value: state.writeBacks },
    ]);

    renderStepControls(root.querySelector("[data-lab-controls]"), {
      onPrevious: () => dispatch({ type: "PREVIOUS_STEP" }),
      onNext: () => dispatch({ type: "NEXT_STEP" }),
      onReset: () => dispatch({ type: "RESET" }),
      canGoBack: state.step > 0,
      canGoForward: state.step < state.history.length,
    });
  },
}));

if (lab) {
  document.querySelector("[data-cpu0-read]")?.addEventListener("click", () => lab.dispatch({ type: "CPU0_READ" }));
  document.querySelector("[data-cpu0-write]")?.addEventListener("click", () => lab.dispatch({ type: "CPU0_WRITE" }));
  document.querySelector("[data-cpu0-evict]")?.addEventListener("click", () => lab.dispatch({ type: "CPU0_EVICT" }));
  document.querySelector("[data-cpu1-read]")?.addEventListener("click", () => lab.dispatch({ type: "CPU1_READ" }));
  document.querySelector("[data-cpu1-write]")?.addEventListener("click", () => lab.dispatch({ type: "CPU1_WRITE" }));
  document.querySelector("[data-cpu1-evict]")?.addEventListener("click", () => lab.dispatch({ type: "CPU1_EVICT" }));
}

mountCodeTabs(document.querySelector("[data-lab-code-tabs]"));

renderQuiz(document.querySelector("[data-lab-quiz]"), {
  question: "CPU 0 reads a line nobody else holds and takes it Exclusive. CPU 0 then writes to that same line. What bus traffic does this write cause?",
  choices: [
    "An invalidation broadcast to every other core",
    "None — Exclusive to Modified is a silent local upgrade",
    "A write-back to memory before the write is allowed",
    "A cache-to-cache transfer from another core",
  ],
  correctIndex: 1,
});
