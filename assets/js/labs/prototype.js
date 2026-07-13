// Mounts the three prototype demos on /lab/ using the shared framework
// (assets/js/core/lab-framework.js). This is the "migrate prototype
// interactions" step for plab-011: the page-level tab switcher between the
// three demos is a plain roving-tabindex tablist (assets/js/core/keyboard.js);
// each demo itself is an independent lab definition mounted onto its own
// <section>. Not a reusable "lab" in the plab-010/lab.json sense — there's no
// content/labs/ entry for these — just a proof that the framework works.
import { initTablist } from "../core/keyboard.js";
import { createLabDefinition, mountLab } from "../core/lab-framework.js";
import { mountRegistry } from "../core/registry.js";
import { falseSharingInitialState, falseSharingReducer, announceFalseSharing } from "./false-sharing-reducer.js";
import { ringBufferInitialState, ringBufferReducer, announceRingBuffer, ringBufferCapacity } from "./ring-buffer-reducer.js";
import { jitPipelineInitialState, jitPipelineReducer, announceJitPipeline, jitStageLabels } from "./jit-pipeline-reducer.js";

mountRegistry(document.querySelector("[data-lab-registry-root]"));

// Page-level tab switcher between the three demo sections.
const nav = document.querySelector('.lab-nav[role="tablist"]');
if (nav) {
  initTablist(nav, {
    onSelect: tab => {
      const target = tab.dataset.labTarget;
      nav.querySelectorAll("[data-lab-target]").forEach(b => b.classList.toggle("active", b === tab));
      document.querySelectorAll(".lab-view").forEach(view => view.classList.toggle("active", view.id === target));
    },
  });
}

// False sharing
const falseSharing = mountLab(document.getElementById("false-sharing"), createLabDefinition({
  metadata: { id: "false-sharing-demo" },
  initialState: falseSharingInitialState,
  events: ["COHERENCE_STEP", "RESET"],
  reducer: falseSharingReducer,
  announce: announceFalseSharing,
  render(state, { root }) {
    const bus = root.querySelector(".coherence-bus");
    root.querySelectorAll(".cpu .cache-cell").forEach((cell, i) => {
      const cpu = Number(cell.closest(".cpu").dataset.cpu);
      cell.classList.toggle("hot", cpu === state.writeSide && i === 3);
    });
    root.querySelectorAll("[data-state]").forEach(el => {
      el.textContent = Number(el.closest(".cpu").dataset.cpu) === state.writeSide ? "Modified" : "Invalid";
    });
    bus?.classList.remove("pulse");
    void bus?.offsetWidth;
    bus?.classList.add("pulse");
  },
}));
document.querySelector("#false-sharing [data-coherence-step]")
  ?.addEventListener("click", () => falseSharing?.dispatch({ type: "COHERENCE_STEP" }));

// Ring buffer
const ringBuffer = mountLab(document.getElementById("ring-buffer"), createLabDefinition({
  metadata: { id: "ring-buffer-demo" },
  initialState: ringBufferInitialState,
  events: ["PRODUCE", "CONSUME", "RESET"],
  reducer: ringBufferReducer,
  announce: announceRingBuffer,
  render(state, { root }) {
    root.querySelectorAll(".ring .slot").forEach((slot, i) => slot.classList.toggle("filled", i < state.size));
    root.querySelector("[data-head]")?.replaceChildren(document.createTextNode(String(state.head)));
    root.querySelector("[data-tail]")?.replaceChildren(document.createTextNode(String(state.tail)));
    root.querySelector("[data-size]")?.replaceChildren(document.createTextNode(String(state.size)));
    const counter = root.querySelector(".counter");
    if (counter) counter.textContent = `capacity = ${ringBufferCapacity}`;
  },
}));
document.querySelector("#ring-buffer [data-produce]")
  ?.addEventListener("click", () => ringBuffer?.dispatch({ type: "PRODUCE" }));
document.querySelector("#ring-buffer [data-consume]")
  ?.addEventListener("click", () => ringBuffer?.dispatch({ type: "CONSUME" }));

// JIT pipeline
const jitPipeline = mountLab(document.getElementById("jit-pipeline"), createLabDefinition({
  metadata: { id: "jit-pipeline-demo" },
  initialState: jitPipelineInitialState,
  events: ["JIT_STEP", "RESET"],
  reducer: jitPipelineReducer,
  announce: announceJitPipeline,
  render(state, { root }) {
    root.querySelectorAll(".jit-node").forEach((node, i) => {
      node.classList.toggle("active", i === state.stageIndex);
      node.textContent = jitStageLabels[i];
    });
  },
}));
document.querySelector("#jit-pipeline [data-jit-step]")
  ?.addEventListener("click", () => jitPipeline?.dispatch({ type: "JIT_STEP" }));
