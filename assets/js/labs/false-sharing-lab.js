// Bootstrap for /lab/false-sharing/ — mounts the interactive cache-line
// model, the Java/Rust code tabs and the review quiz onto the static page.
// Contract: docs/lab-framework.md "Progressive enhancement bootstrap".
import { createLabDefinition, mountLab } from "../core/lab-framework.js";
import { renderScenarioSelector, renderStepControls, renderStateInspector, mountCodeTabs, renderQuiz } from "../core/components.js";
import {
  falseSharingInitialState,
  falseSharingReducer,
  falseSharingScenarios,
  falseSharingEventLabel,
  announceFalseSharing,
} from "./false-sharing-scenarios.js";

const SCENARIO_COPY = {
  "shared-line": "Both counters live on one cache line. Every write from either CPU invalidates the other's copy — alternate CPU 0 write / CPU 1 write and watch invalidations climb.",
  "padded-line": "Each counter now has its own line. Writes from either CPU never touch the other's line — invalidations and transfers stay at zero no matter how you alternate writes.",
  "read-mostly": "Same shared line as “Shared line”, but try only CPU 0 write and CPU 1 read. Reads don't invalidate — a transfer still happens when CPU 1 catches up, but far less often than the symmetric write/write pattern.",
};

const LINE_STATE_LABEL = { shared: "Shared", modified: "Modified", invalid: "Invalid" };
const LINE_STATE_BADGE = { shared: "is-idle", modified: "is-active", invalid: "is-danger" };

function renderCpu(root, index, cpuState) {
  const cpu = root.querySelector(`.cpu[data-cpu="${index}"]`);
  if (!cpu) return;
  const badge = cpu.querySelector("[data-cpu-badge]");
  if (badge) {
    badge.textContent = LINE_STATE_LABEL[cpuState.lineState];
    badge.className = `state-badge ${LINE_STATE_BADGE[cpuState.lineState]}`;
  }
  const op = cpu.querySelector("[data-cpu-op]");
  if (op) op.textContent = `Last operation: ${cpuState.operation}`;
  const cell = cpu.querySelector("[data-cpu-cell]");
  if (cell) cell.classList.toggle("hot", cpuState.lineState === "modified");
}

const stage = document.querySelector("[data-lab-stage]");

const lab = mountLab(stage, createLabDefinition({
  metadata: { id: "false-sharing" },
  initialState: falseSharingInitialState,
  events: [
    "CPU0_WRITE", "CPU1_WRITE", "CPU0_READ", "CPU1_READ",
    "NEXT_STEP", "PREVIOUS_STEP", "RESET", "SELECT_SCENARIO",
  ],
  scenarios: falseSharingScenarios,
  reducer: falseSharingReducer,
  announce: announceFalseSharing,
  render(state, { dispatch, root }) {
    const nav = root.querySelector("[data-lab-scenario-nav]");
    renderScenarioSelector(nav, {
      scenarios: falseSharingScenarios,
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
      const entries = state.history.slice(0, state.step).map(falseSharingEventLabel);
      eventLog.replaceChildren(...["Initial state — both lines shared.", ...entries].map(text => {
        const li = document.createElement("li");
        li.textContent = text;
        return li;
      }));
    }

    renderStateInspector(root.querySelector("[data-lab-inspector]"), [
      { label: "Scenario", value: falseSharingScenarios.find(s => s.id === state.scenario)?.label ?? state.scenario },
      { label: "Step", value: `${state.step} of ${state.history.length}` },
      { label: "Owner", value: state.owner === null ? "none" : `CPU ${state.owner}` },
      { label: "Invalidations", value: state.invalidations },
      { label: "Transfers", value: state.transfers },
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
  document.querySelector("[data-cpu0-write]")?.addEventListener("click", () => lab.dispatch({ type: "CPU0_WRITE" }));
  document.querySelector("[data-cpu0-read]")?.addEventListener("click", () => lab.dispatch({ type: "CPU0_READ" }));
  document.querySelector("[data-cpu1-write]")?.addEventListener("click", () => lab.dispatch({ type: "CPU1_WRITE" }));
  document.querySelector("[data-cpu1-read]")?.addEventListener("click", () => lab.dispatch({ type: "CPU1_READ" }));
}

mountCodeTabs(document.querySelector("[data-lab-code-tabs]"));

renderQuiz(document.querySelector("[data-lab-quiz]"), {
  question: "Two threads each increment their own independent counter, no shared invariant, no lock. Throughput is far lower than running either thread alone. What is the most likely cause?",
  choices: [
    "A data race on one of the counters",
    "Lock contention between the two threads",
    "False sharing: the counters share a cache line",
    "The JIT failed to inline the increment",
  ],
  correctIndex: 2,
});
