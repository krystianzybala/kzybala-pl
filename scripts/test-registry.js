#!/usr/bin/env node
// Unit tests for the lab-registry catalogue: pure filter logic
// (assets/js/core/registry.js) and the roadmap builder
// (assets/js/core/roadmap.js). No DOM/browser required — a minimal stub
// sized to exactly what mountRegistry touches (docs/new-lab-workflow.md:
// "zero npm dependencies by design", same approach as scripts/lib/dom-stub.js).
import { test } from "node:test";
import assert from "node:assert/strict";
import { mountRegistry } from "../assets/js/core/registry.js";
import { buildRoadmapItems } from "../assets/js/core/roadmap.js";

const LABS = [
  { id: "a-lab", title: "A Lab", status: "stable", level: 1, difficulty: "beginner", durationMinutes: 10, topics: ["x"] },
  { id: "b-lab", title: "B Lab", status: "stable", level: 2, difficulty: "advanced", durationMinutes: 20, topics: ["y"] },
  { id: "c-lab", title: "C Lab", status: "stable", level: 3, difficulty: "advanced", durationMinutes: 30, topics: ["x", "y"] },
];

// --- DOM stub sized to what mountRegistry(root) touches ---

function createSelectStub(field, dynamic) {
  const listeners = {};
  return {
    dataset: { labFilter: field },
    value: "",
    hasAttribute: name => (name === "data-lab-filter-dynamic" ? dynamic : false),
    append() {}, // dynamic <option> population — not asserted on here
    addEventListener(type, handler) { (listeners[type] ??= []).push(handler); },
    selectValue(value) { this.value = value; (listeners.change || []).forEach(h => h()); },
  };
}

function createListStub() {
  return { children: [], replaceChildren(...items) { this.children = items; } };
}

function createRootStub(selects) {
  const list = createListStub();
  const empty = { hidden: true };
  const count = { textContent: "" };
  return {
    list, empty, count, selects,
    querySelector(sel) {
      if (sel === "[data-lab-registry]") return list;
      if (sel === "[data-lab-registry-empty]") return empty;
      if (sel === "[data-lab-filter-count]") return count;
      return null;
    },
    querySelectorAll(sel) {
      return sel === "[data-lab-filter]" ? selects : [];
    },
  };
}

async function mount() {
  const topicSelect = createSelectStub("topics", true);
  const difficultySelect = createSelectStub("difficulty", true);
  const root = createRootStub([topicSelect, difficultySelect]);
  globalThis.document = { createElement: () => ({ className: "", href: "", innerHTML: "" }) };
  globalThis.fetch = async () => ({ ok: true, json: async () => LABS });
  await mountRegistry(root);
  return { root, topicSelect, difficultySelect };
}

test("lab registry: default state shows every lab and hides the empty message", async () => {
  const { root } = await mount();
  assert.equal(root.list.children.length, 3);
  assert.equal(root.empty.hidden, true);
  assert.equal(root.count.textContent, "Showing 3 of 3 laboratories");
});

test("lab registry: a filter matching some labs narrows the list and keeps the empty message hidden", async () => {
  const { root, topicSelect } = await mount();
  topicSelect.selectValue("x"); // matches a-lab and c-lab
  assert.equal(root.list.children.length, 2);
  assert.equal(root.empty.hidden, true);
  assert.equal(root.count.textContent, "Showing 2 of 3 laboratories");
});

test("lab registry: a filter combination matching nothing shows the empty message", async () => {
  const { root, topicSelect, difficultySelect } = await mount();
  topicSelect.selectValue("y"); // b-lab, c-lab
  difficultySelect.selectValue("beginner"); // only a-lab is beginner — zero overlap
  assert.equal(root.list.children.length, 0);
  assert.equal(root.empty.hidden, false);
  assert.equal(root.count.textContent, "Showing 0 of 3 laboratories");
});

test("lab registry: clearing filters after a zero-result state restores the full list and re-hides the message", async () => {
  const { root, topicSelect, difficultySelect } = await mount();
  topicSelect.selectValue("y");
  difficultySelect.selectValue("beginner");
  assert.equal(root.empty.hidden, false);
  topicSelect.selectValue("");
  difficultySelect.selectValue("");
  assert.equal(root.list.children.length, 3);
  assert.equal(root.empty.hidden, true);
});

// --- roadmap builder (assets/js/core/roadmap.js) ---

const ROADMAP_LABS = [
  { id: "shipped-1", title: "Shipped One", status: "stable", level: 1, topics: ["x"], durationMinutes: 10 },
  { id: "shipped-2", title: "Shipped Two", status: "stable", level: 2, topics: ["y"], durationMinutes: 10 },
  { id: "draft-1", title: "Draft One", status: "draft", level: 3, topics: ["z"], durationMinutes: 10 },
];

test("roadmap: stable labs become Shipped, draft labs become In progress, in level order", () => {
  const items = buildRoadmapItems(ROADMAP_LABS, []);
  assert.deepEqual(items.map(i => [i.title, i.badgeLabel]), [
    ["Shipped One", "Shipped"],
    ["Shipped Two", "Shipped"],
    ["Draft One", "In progress"],
  ]);
});

test("roadmap: canonical planned items are appended last, always labelled Planned", () => {
  const planned = [{ title: "Future Idea", description: "Not started yet." }];
  const items = buildRoadmapItems(ROADMAP_LABS, planned);
  assert.equal(items.length, 4);
  const last = items[items.length - 1];
  assert.deepEqual([last.title, last.badgeLabel, last.description], ["Future Idea", "Planned", "Not started yet."]);
});

test("roadmap: a status change in metadata is reflected without editing any hard-coded list", () => {
  const relabelled = ROADMAP_LABS.map(lab => (lab.id === "draft-1" ? { ...lab, status: "stable" } : lab));
  const items = buildRoadmapItems(relabelled, []);
  assert.ok(items.every(i => i.badgeLabel === "Shipped"), "every lab should now read Shipped once its metadata says stable");
});

test("roadmap: no planned items yields no Planned entries (empty canonical file is valid)", () => {
  const items = buildRoadmapItems(ROADMAP_LABS, []);
  assert.ok(items.every(i => i.badgeLabel !== "Planned"));
});
