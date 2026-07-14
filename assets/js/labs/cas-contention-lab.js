// Bootstrap for /lab/cas-contention/ — mounts the interactive CAS-retry
// contention model, the Java/Rust code tabs and the review quiz onto the
// static page. Contract: docs/lab-framework.md "Progressive enhancement".
import { createLabDefinition, mountLab } from "../core/lab-framework.js";
import { renderScenarioSelector, renderStepControls, renderStateInspector, mountCodeTabs, renderQuiz } from "../core/components.js";
import {
  casInitialState,
  casReducer,
  casScenarios,
  casScenarioMaxSteps,
  casEventLabel,
  announceCas,
} from "./cas-contention-scenarios.js";

const SCENARIO_COPY = {
  "single-thread": "One thread, no contention. Every CAS succeeds on the first attempt — the baseline before any of the failure modes below exist.",
  "two-contenders": "Two threads racing the same CAS loop. Step through and watch the first failure: whichever thread doesn't win a given round must re-read and retry.",
  "many-contenders": "Four threads racing the same loop. Compare the failure count against two-contenders — contention collapse: a much higher fraction of attempts are wasted retries.",
  "fixed-backoff": "Same four contenders, but a failed thread now waits a fixed delay before retrying instead of retrying immediately. Compare the failure count against many-contenders.",
  "exponential-backoff": "Same four contenders, but the delay after a failure doubles with each consecutive miss for that thread (capped), with a little per-thread jitter. Compare against fixed backoff — its real advantage is adapting to a thread's own recent luck, not necessarily beating a well-tuned fixed delay at one contention level.",
  "single-writer": "No CAS at all: one thread owns the value outright and just writes it. Zero retries, zero ownership transfers — the architectural alternative to managing contention.",
};

function renderContender(root, contender) {
  const box = root.querySelector(`.cpu[data-contender="${contender.id}"]`);
  if (!box) return;
  const status = box.querySelector("[data-contender-status]");
  if (status) {
    if (contender.done) {
      status.textContent = "Done";
      status.className = "state-badge is-success";
    } else if (contender.backoffWait > 0) {
      status.textContent = `Backing off (${contender.backoffWait})`;
      status.className = "state-badge is-warning";
    } else {
      status.textContent = "Active";
      status.className = "state-badge is-idle";
    }
  }
  const progress = box.querySelector("[data-contender-progress]");
  if (progress) progress.textContent = `Successes: ${contender.successes} · Failures: ${contender.failureStreak > 0 ? contender.failureStreak : 0} streak`;
}

const stage = document.querySelector("[data-lab-stage]");

const lab = mountLab(stage, createLabDefinition({
  metadata: { id: "cas-contention" },
  initialState: casInitialState,
  events: ["NEXT_STEP", "PREVIOUS_STEP", "RESET", "SELECT_SCENARIO"],
  scenarios: casScenarios,
  reducer: casReducer,
  announce: announceCas,
  render(state, { dispatch, root }) {
    const nav = root.querySelector("[data-lab-scenario-nav]");
    renderScenarioSelector(nav, {
      scenarios: casScenarios,
      current: state.scenario,
      onSelect: scenario => dispatch({ type: "SELECT_SCENARIO", scenario }),
    });

    const description = root.querySelector("[data-scenario-description]");
    if (description) description.textContent = SCENARIO_COPY[state.scenario];

    const grid = root.querySelector("[data-lab-grid]");
    if (grid) {
      grid.className = `cpu-grid${state.contenders.length > 2 ? " many" : ""}`;
      grid.replaceChildren(...state.contenders.map(contender => {
        const box = document.createElement("div");
        box.className = "cpu";
        box.dataset.contender = String(contender.id);

        const label = document.createElement("p");
        label.className = "cpu-label";
        label.textContent = `T${contender.id}`;

        const status = document.createElement("span");
        status.className = "state-badge is-idle";
        status.setAttribute("data-contender-status", "");
        status.textContent = "Active";

        const progress = document.createElement("p");
        progress.className = "counter";
        progress.setAttribute("data-contender-progress", "");
        progress.textContent = "Successes: 0";

        box.append(label, status, progress);
        return box;
      }));
      state.contenders.forEach(c => renderContender(root, c));
    }

    const eventLog = root.querySelector("[data-lab-event-log]");
    if (eventLog) {
      eventLog.replaceChildren(...["Initial state — no CAS attempts yet.", ...state.log.map(casEventLabel)].map(text => {
        const li = document.createElement("li");
        li.textContent = text;
        return li;
      }));
    }

    const maxSteps = casScenarioMaxSteps(state.scenario);
    renderStateInspector(root.querySelector("[data-lab-inspector]"), [
      { label: "Scenario", value: casScenarios.find(s => s.id === state.scenario)?.label ?? state.scenario },
      { label: "Step", value: `${state.step} of ${maxSteps}` },
      { label: "Value", value: state.value },
      { label: "Successful CAS", value: state.successfulCas },
      { label: "Failed CAS", value: state.failedCas },
      { label: "Retries", value: state.retries },
      { label: "Ownership transfers", value: state.ownershipTransfers },
      { label: "Completion (simulated steps)", value: state.completionStep ?? "—" },
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

mountCodeTabs(document.querySelector("[data-lab-code-tabs]"));

renderQuiz(document.querySelector("[data-lab-quiz]"), {
  question: "Four threads repeatedly CAS-increment the same shared counter with no backoff. As you add more contending threads, what happens to the fraction of attempts that fail?",
  choices: [
    "It stays constant — CAS failure rate doesn't depend on contender count",
    "It grows — a larger share of attempts collide and are wasted retries, which is what \"contention collapse\" means",
    "It shrinks, because more threads means more chances someone succeeds",
    "It's undefined behaviour",
  ],
  correctIndex: 1,
});
