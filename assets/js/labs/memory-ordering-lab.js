// Bootstrap for /lab/memory-ordering/ — mounts the interactive store-buffer
// / happens-before model, the Java/Rust code tabs and the review quiz onto
// the static page. Contract: docs/lab-framework.md "Progressive enhancement".
import { createLabDefinition, mountLab } from "../core/lab-framework.js";
import { renderScenarioSelector, renderStepControls, renderStateInspector, mountCodeTabs, renderQuiz } from "../core/components.js";
import {
  memoryOrderingInitialState,
  memoryOrderingReducer,
  memoryOrderingScenarios,
  memoryOrderingScenarioMaxSteps,
  memoryOrderingEventLabel,
  memoryOrderingOutcome,
  announceMemoryOrdering,
} from "./memory-ordering-scenarios.js";

const SCENARIO_COPY = {
  "broken-publication": "T0 writes data, then flag — both with plain access. Step through and watch the flag's write get flushed to memory before data's, letting T1 see flag=1 while data is still 0.",
  "release-acquire": "Same two writes, but flag is now a release write and T1's read of it is an acquire read. Step through and see that once T1 observes flag=1, data is guaranteed visible too.",
  "relaxed-counter": "Two threads increment one shared counter with relaxed read-modify-write. Step through and watch the total always come out correct — relaxed guarantees atomicity, just not ordering relative to anything else.",
  "store-buffering": "T0 writes x then reads y; T1 writes y then reads x — with no dependency between the variables at all. Toggle the ordering below to see the classic store-buffering effect, and how SeqCst forbids it.",
};

const MEMORY_FIELDS_BY_SCENARIO = {
  "broken-publication": ["data", "flag"],
  "release-acquire": ["data", "flag"],
  "relaxed-counter": ["counter"],
  "store-buffering": ["x", "y"],
};

function renderThread(root, index, threadState) {
  const box = root.querySelector(`.cpu[data-thread="${index}"]`);
  if (!box) return;
  const buffer = box.querySelector("[data-thread-buffer]");
  if (buffer) {
    buffer.textContent = threadState.buffer.length === 0
      ? "Buffer: empty"
      : `Buffer: ${threadState.buffer.map(e => `${e.var}=${e.value} (${e.mode})`).join(", ")}`;
  }
}

function renderEdges(root, state) {
  const list = root.querySelector("[data-happens-before]");
  if (!list) return;
  if (state.edges.length === 0) {
    list.replaceChildren(Object.assign(document.createElement("li"), { textContent: "No synchronizes-with edges yet." }));
    return;
  }
  list.replaceChildren(...state.edges.map(edge => {
    const li = document.createElement("li");
    li.textContent = `Step ${edge.fromStep + 1} (synchronizing write) → step ${edge.toStep + 1} (acquiring read): synchronizes-with, establishing happens-before.`;
    return li;
  }));
}

const stage = document.querySelector("[data-lab-stage]");

const lab = mountLab(stage, createLabDefinition({
  metadata: { id: "memory-ordering" },
  initialState: memoryOrderingInitialState,
  events: ["NEXT_STEP", "PREVIOUS_STEP", "RESET", "SELECT_SCENARIO", "SELECT_ORDERING"],
  scenarios: memoryOrderingScenarios,
  reducer: memoryOrderingReducer,
  announce: announceMemoryOrdering,
  render(state, { dispatch, root }) {
    const nav = root.querySelector("[data-lab-scenario-nav]");
    renderScenarioSelector(nav, {
      scenarios: memoryOrderingScenarios,
      current: state.scenario,
      onSelect: scenario => dispatch({ type: "SELECT_SCENARIO", scenario }),
    });

    const description = root.querySelector("[data-scenario-description]");
    if (description) description.textContent = SCENARIO_COPY[state.scenario];

    const orderingControls = root.querySelector("[data-ordering-controls]");
    if (orderingControls) orderingControls.hidden = state.scenario !== "store-buffering";
    const relaxedBtn = root.querySelector("[data-ordering-relaxed]");
    const seqcstBtn = root.querySelector("[data-ordering-seqcst]");
    if (relaxedBtn) relaxedBtn.setAttribute("aria-pressed", String(state.ordering === "relaxed"));
    if (seqcstBtn) seqcstBtn.setAttribute("aria-pressed", String(state.ordering === "seqcst"));

    renderThread(root, 0, state.thread0);
    renderThread(root, 1, state.thread1);
    renderEdges(root, state);

    const eventLog = root.querySelector("[data-lab-event-log]");
    if (eventLog) {
      eventLog.replaceChildren(...["Initial state — no instructions executed.", ...state.log.map(memoryOrderingEventLabel)].map(text => {
        const li = document.createElement("li");
        li.textContent = text;
        return li;
      }));
    }

    const outcome = memoryOrderingOutcome(state);
    const outcomeEl = root.querySelector("[data-lab-outcome]");
    if (outcomeEl) {
      outcomeEl.textContent = outcome ?? "Step through to the end to see the outcome.";
      outcomeEl.className = `state-badge ${outcome ? "is-active" : "is-idle"}`;
    }

    const memoryFields = (MEMORY_FIELDS_BY_SCENARIO[state.scenario] ?? []).map(key => ({
      label: key, value: state.memory[key] ?? 0,
    }));
    const maxSteps = memoryOrderingScenarioMaxSteps(state.scenario, state.ordering);
    renderStateInspector(root.querySelector("[data-lab-inspector]"), [
      { label: "Scenario", value: memoryOrderingScenarios.find(s => s.id === state.scenario)?.label ?? state.scenario },
      { label: "Ordering", value: state.ordering },
      { label: "Step", value: `${state.step} of ${maxSteps}` },
      ...memoryFields,
      { label: "Happens-before edges", value: state.edges.length },
    ]);

    renderStepControls(root.querySelector("[data-lab-controls]"), {
      onPrevious: () => dispatch({ type: "PREVIOUS_STEP" }),
      onNext: () => dispatch({ type: "NEXT_STEP" }),
      onReset: () => dispatch({ type: "RESET" }),
      canGoBack: state.step > 0,
      canGoForward: state.step < maxSteps,
    });
  },
}));

if (lab) {
  document.querySelector("[data-ordering-relaxed]")?.addEventListener("click", () => lab.dispatch({ type: "SELECT_ORDERING", ordering: "relaxed" }));
  document.querySelector("[data-ordering-seqcst]")?.addEventListener("click", () => lab.dispatch({ type: "SELECT_ORDERING", ordering: "seqcst" }));
}

mountCodeTabs(document.querySelector("[data-lab-code-tabs]"));

renderQuiz(document.querySelector("[data-lab-quiz]"), {
  question: "Two threads run the classic store-buffering litmus test (T0: x=1; read y — T1: y=1; read x) with Relaxed atomics, and both threads observe 0 for the other's write. What does this tell you?",
  choices: [
    "One of the atomics is broken — this should never happen",
    "This is a legal, if surprising, outcome under weak/relaxed ordering — SeqCst is what forbids it",
    "The compiler introduced a data race",
    "Relaxed atomics are not actually atomic",
  ],
  correctIndex: 1,
});
