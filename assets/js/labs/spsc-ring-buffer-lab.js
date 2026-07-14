// Bootstrap for /lab/spsc-ring-buffer/ — mounts the interactive SPSC
// ring-buffer model, the Java/Rust code tabs and the review quiz onto the
// static page. Contract: docs/lab-framework.md "Progressive enhancement".
import { createLabDefinition, mountLab } from "../core/lab-framework.js";
import { renderScenarioSelector, renderStepControls, renderStateInspector, mountCodeTabs, renderQuiz } from "../core/components.js";
import {
  spscRingBufferInitialState,
  spscRingBufferReducer,
  spscRingBufferScenarios,
  spscRingBufferScenarioMaxSteps,
  spscRingBufferEventLabel,
  announceSpscRingBuffer,
} from "./spsc-ring-buffer-scenarios.js";

const SCENARIO_COPY = {
  "normal": "Two full produce/consume cycles: reserve, write, publish, then read, acknowledge. The baseline correct flow every other scenario is compared against.",
  "wrap-around": "A 2-slot buffer, filled and fully drained twice over. Watch the slot index in the last produce cycle land back on slot 0 — the ring wrapping around, not an error.",
  "full": "Two produces fill a 2-slot buffer with nothing consumed. The third reservation attempt is rejected — this is backpressure, not a crash.",
  "empty": "A consumer reads before anything has been produced and is correctly told the buffer is empty (starved), then a normal produce/consume pair succeeds.",
  "cached-cursor": "The producer caches the consumer's tail so it doesn't need to re-read it on every reservation. Watch the 5th reservation: the stale cache pessimistically reports \"full\", forcing a refresh that reveals the real room the consumer already freed.",
  "batch": "One reservation, three writes, one publish — then three reads, one acknowledgement. Batch publication trades one release-store (and one tail update) for three, without batching the reads themselves.",
  "bug-ordering": "BUG: the producer publishes a slot (advances head) before writing its payload. The consumer reads exactly what was left in that slot beforehand — silently wrong data, not a crash.",
  "bug-overwrite": "BUG: the producer never checks the consumer's tail before reserving. Once the buffer is genuinely full, the next write silently overwrites a slot the consumer hasn't read yet — the earlier value is lost forever.",
};

function renderActor(root, name, status, detail) {
  const box = root.querySelector(`.cpu[data-actor="${name}"]`);
  if (!box) return;
  const statusEl = box.querySelector("[data-actor-status]");
  if (statusEl) {
    statusEl.textContent = status.label;
    statusEl.className = `state-badge ${status.cls}`;
  }
  const detailEl = box.querySelector("[data-actor-detail]");
  if (detailEl) detailEl.textContent = detail;
}

function producerStatus(state) {
  if (!state.producerReserved) return { label: "Idle", cls: "is-idle" };
  if (state.producerPublishedEarly) return { label: "Published early (bug)", cls: "is-danger" };
  if (state.producerWrittenCount < state.producerPendingCount) return { label: "Writing", cls: "is-active" };
  return { label: "Ready to publish", cls: "is-warning" };
}

function consumerStatus(state) {
  if (state.consumerHeldCount === 0) return { label: "Idle", cls: "is-idle" };
  return { label: "Holding (ready to ack)", cls: "is-warning" };
}

function slotStatus(slot) {
  if (slot.value === null) return { label: "Empty", cls: "is-idle" };
  if (slot.published) return { label: "Published", cls: "is-warning" };
  return { label: "Reserved", cls: "is-active" };
}

const stage = document.querySelector("[data-lab-stage]");

const lab = mountLab(stage, createLabDefinition({
  metadata: { id: "spsc-ring-buffer" },
  initialState: spscRingBufferInitialState,
  events: ["NEXT_STEP", "PREVIOUS_STEP", "RESET", "SELECT_SCENARIO"],
  scenarios: spscRingBufferScenarios,
  reducer: spscRingBufferReducer,
  announce: announceSpscRingBuffer,
  render(state, { dispatch, root }) {
    const nav = root.querySelector("[data-lab-scenario-nav]");
    renderScenarioSelector(nav, {
      scenarios: spscRingBufferScenarios,
      current: state.scenario,
      onSelect: scenario => dispatch({ type: "SELECT_SCENARIO", scenario }),
    });

    const description = root.querySelector("[data-scenario-description]");
    if (description) description.textContent = SCENARIO_COPY[state.scenario];

    const actorGrid = root.querySelector("[data-lab-actors]");
    if (actorGrid) {
      actorGrid.replaceChildren(...["producer", "consumer"].map(name => {
        const box = document.createElement("div");
        box.className = "cpu";
        box.dataset.actor = name;
        const label = document.createElement("p");
        label.className = "cpu-label";
        label.textContent = name === "producer" ? "Producer" : "Consumer";
        const status = document.createElement("span");
        status.className = "state-badge is-idle";
        status.setAttribute("data-actor-status", "");
        const detail = document.createElement("p");
        detail.className = "counter";
        detail.setAttribute("data-actor-detail", "");
        box.append(label, status, detail);
        return box;
      }));
      renderActor(root, "producer", producerStatus(state), `Reserved: ${state.reserveIndex} · Published: ${state.head} · Cache hits/refreshes: ${state.producerCacheHits}/${state.producerCacheRefreshes}`);
      renderActor(root, "consumer", consumerStatus(state), `Read: ${state.readIndex} · Acknowledged: ${state.tail} · Cache hits/refreshes: ${state.consumerCacheHits}/${state.consumerCacheRefreshes}`);
    }

    const slotGrid = root.querySelector("[data-lab-grid]");
    if (slotGrid) {
      slotGrid.className = `cpu-grid${state.slots.length > 2 ? " many" : ""}`;
      slotGrid.replaceChildren(...state.slots.map((slot, i) => {
        const box = document.createElement("div");
        box.className = "cpu";
        const label = document.createElement("p");
        label.className = "cpu-label";
        label.textContent = `Slot ${i}`;
        const status = document.createElement("span");
        const st = slotStatus(slot);
        status.className = `state-badge ${st.cls}`;
        status.textContent = st.label;
        const value = document.createElement("p");
        value.className = "counter";
        value.textContent = `Value: ${slot.value === null ? "—" : slot.value}`;
        box.append(label, status, value);
        return box;
      }));
    }

    const eventLog = root.querySelector("[data-lab-event-log]");
    if (eventLog) {
      eventLog.replaceChildren(...["Initial state — buffer empty, nothing produced or consumed yet.", ...state.log.map(spscRingBufferEventLabel)].map(text => {
        const li = document.createElement("li");
        li.textContent = text;
        return li;
      }));
    }

    const maxSteps = spscRingBufferScenarioMaxSteps(state.scenario);
    renderStateInspector(root.querySelector("[data-lab-inspector]"), [
      { label: "Scenario", value: spscRingBufferScenarios.find(s => s.id === state.scenario)?.label ?? state.scenario },
      { label: "Step", value: `${state.step} of ${maxSteps}` },
      { label: "Occupancy (head − tail)", value: state.head - state.tail },
      { label: "Reserved / Published / Read / Acknowledged", value: `${state.reserveIndex} / ${state.head} / ${state.readIndex} / ${state.tail}` },
      { label: "Rejected reservations (full)", value: state.rejectedReservations },
      { label: "Starved reads (empty)", value: state.starvedReads },
      { label: "Batch publishes / acks", value: `${state.batchPublishes} / ${state.batchAcks}` },
      { label: "Overwrite bugs (data lost)", value: state.overwrites },
      { label: "Incorrect reads (stale data)", value: state.incorrectReads },
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
  question: "In the \"Bug: publish before write\" scenario, the producer advances head before writing the slot's payload. What does the consumer observe?",
  choices: [
    "A crash, because the slot is uninitialized",
    "Whatever value was already in that slot beforehand — silently wrong data, with no error at all",
    "The correct value, because the consumer waits for the write",
    "An empty-buffer error",
  ],
  correctIndex: 1,
});
