// Bootstrap for /lab/thread-per-core/ — mounts the interactive
// worker-pool-vs-thread-per-core model, the Java/Rust code tabs and the
// review quiz onto the static page. Contract: docs/lab-framework.md
// "Progressive enhancement".
import { createLabDefinition, mountLab } from "../core/lab-framework.js";
import { renderScenarioSelector, renderStepControls, renderStateInspector, mountCodeTabs, renderQuiz } from "../core/components.js";
import {
  threadPerCoreInitialState,
  threadPerCoreReducer,
  threadPerCoreScenarios,
  threadPerCoreScenarioMaxSteps,
  threadPerCoreEventLabel,
  announceThreadPerCore,
} from "./thread-per-core-scenarios.js";

const SCENARIO_COPY = {
  "worker-pool": "Four requests, four \"workers\" — but every request touches the same shared state, so each one still needs the shared-state lock. Watch it take 4 turns to drain 4 requests: the lock serializes them regardless of worker count.",
  "owned-state": "The same four requests, but each is routed directly to the core that owns its partition. No shared state, no lock — all four cores process in parallel, in a single turn.",
  "cross-core-handoff": "Four requests each arrive on the \"wrong\" core and must be handed off to the core that actually owns their partition. Compare against \"Thread-per-core ownership\": the same four requests now take 2 turns instead of 1 — that's the handoff's latency cost.",
  "hot-partition": "Six requests, all for the same popular partition. Core 1 queues up and eventually has to reject requests (backpressure), while cores 0, 2, and 3 sit completely idle — correct partitioning does not by itself fix a skewed key distribution.",
  "scheduler-migration": "Four requests are processed correctly, then core 2's OS thread is migrated to a different physical CPU, then four more requests are processed — still correctly. Migration is a locality problem, not a correctness problem.",
  "backpressure": "Five requests arrive at once for one core with a 3-slot inbox. Two are rejected immediately rather than letting the queue grow without bound — the same bounded-queue discipline as the SPSC Ring Buffer lab, applied to a core's inbox.",
};

function renderCore(root, core) {
  const box = root.querySelector(`.cpu[data-core="${core.id}"]`);
  if (!box) return;
  const status = box.querySelector("[data-core-status]");
  if (status) {
    if (core.queue.length >= 3) {
      status.textContent = `Queue: ${core.queue.length}/3 (near capacity)`;
      status.className = "state-badge is-warning";
    } else if (core.queue.length > 0) {
      status.textContent = `Queue: ${core.queue.length}/3`;
      status.className = "state-badge is-active";
    } else {
      status.textContent = "Idle";
      status.className = "state-badge is-idle";
    }
  }
  const detail = box.querySelector("[data-core-detail]");
  if (detail) {
    const cpuText = core.migrated ? `CPU ${core.currentCpu} (migrated from CPU ${core.homeCpu})` : `CPU ${core.currentCpu} (home)`;
    detail.textContent = `Owns partition ${core.id} · Processed: ${core.processed} · Running on ${cpuText}`;
  }
}

const stage = document.querySelector("[data-lab-stage]");

const lab = mountLab(stage, createLabDefinition({
  metadata: { id: "thread-per-core" },
  initialState: threadPerCoreInitialState,
  events: ["NEXT_STEP", "PREVIOUS_STEP", "RESET", "SELECT_SCENARIO"],
  scenarios: threadPerCoreScenarios,
  reducer: threadPerCoreReducer,
  announce: announceThreadPerCore,
  render(state, { dispatch, root }) {
    const nav = root.querySelector("[data-lab-scenario-nav]");
    renderScenarioSelector(nav, {
      scenarios: threadPerCoreScenarios,
      current: state.scenario,
      onSelect: scenario => dispatch({ type: "SELECT_SCENARIO", scenario }),
    });

    const description = root.querySelector("[data-scenario-description]");
    if (description) description.textContent = SCENARIO_COPY[state.scenario];

    const coreGrid = root.querySelector("[data-lab-grid]");
    if (coreGrid) {
      coreGrid.className = "cpu-grid many";
      coreGrid.replaceChildren(...state.cores.map(core => {
        const box = document.createElement("div");
        box.className = "cpu";
        box.dataset.core = String(core.id);
        const label = document.createElement("p");
        label.className = "cpu-label";
        label.textContent = `Core ${core.id}`;
        const status = document.createElement("span");
        status.className = "state-badge is-idle";
        status.setAttribute("data-core-status", "");
        const detail = document.createElement("p");
        detail.className = "counter";
        detail.setAttribute("data-core-detail", "");
        box.append(label, status, detail);
        return box;
      }));
      state.cores.forEach(core => renderCore(root, core));
    }

    const eventLog = root.querySelector("[data-lab-event-log]");
    if (eventLog) {
      eventLog.replaceChildren(...["Initial state — no requests have arrived yet.", ...state.log.map(threadPerCoreEventLabel)].map(text => {
        const li = document.createElement("li");
        li.textContent = text;
        return li;
      }));
    }

    const maxSteps = threadPerCoreScenarioMaxSteps(state.scenario);
    renderStateInspector(root.querySelector("[data-lab-inspector]"), [
      { label: "Scenario", value: threadPerCoreScenarios.find(s => s.id === state.scenario)?.label ?? state.scenario },
      { label: "Step", value: `${state.step} of ${maxSteps}` },
      { label: "Total requests", value: state.totalRequests },
      { label: "Shared-lock acquisitions", value: state.lockAcquisitions },
      { label: "Cross-core handoffs", value: state.handoffs },
      { label: "Rejected (backpressure)", value: state.rejectedRequests },
      { label: "Scheduler migrations", value: state.migrationEvents },
      { label: "Shared queue depth", value: state.sharedQueue.length },
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
  question: "In the \"Cross-core handoff\" scenario, the same four requests that finish in 1 turn under \"Thread-per-core ownership\" take 2 turns. What does that extra turn represent?",
  choices: [
    "A bug in the simulation",
    "The latency cost of handing a request off to the core that actually owns its partition, instead of it arriving there directly",
    "Lock contention, the same as the shared worker pool",
    "A rejected request being retried",
  ],
  correctIndex: 1,
});
