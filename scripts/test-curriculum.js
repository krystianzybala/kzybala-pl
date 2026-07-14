#!/usr/bin/env node
// Unit tests for the curriculum roadmap: pure grouping/resolution logic and
// the render/filter mount function (assets/js/core/curriculum.js). No
// DOM/browser required — same minimal stub approach as test-registry.js.
import { test } from "node:test";
import assert from "node:assert/strict";
import { mountCurriculum, groupByCategory, resolveLearningPath, CATEGORIES, LEARNING_PATHS } from "../assets/js/core/curriculum.js";
import { loadCurriculum } from "./lib/curriculum.js";

const ENTRIES = [
  { id: "a", title: "A", category: "foundations", path: "foundational", level: 1, focusQuestion: "Q-A", curriculumStatus: "implemented", evidenceMaturity: "reproduced", languages: ["java"], durationMinutes: 20, prerequisites: [], route: "/lab/a/", sourceChange: null, plannedChange: null },
  { id: "b", title: "B", category: "foundations", path: "intermediate", level: 2, focusQuestion: "Q-B", curriculumStatus: "planned", evidenceMaturity: null, languages: ["java"], durationMinutes: 25, prerequisites: ["a"], route: null, sourceChange: null, plannedChange: "plab-999" },
  { id: "c", title: "C", category: "jit-compiler", path: "advanced", level: 1, focusQuestion: "Q-C", curriculumStatus: "planned", evidenceMaturity: null, languages: ["java"], durationMinutes: 30, prerequisites: ["b"], route: null, sourceChange: null, plannedChange: "plab-998" },
];

// --- groupByCategory ---

test("groupByCategory: groups entries by category, sorted by level", () => {
  const groups = groupByCategory(ENTRIES);
  assert.deepEqual([...groups.keys()], ["foundations", "jit-compiler"]);
  assert.deepEqual(groups.get("foundations").map(e => e.id), ["a", "b"]);
});

// --- resolveLearningPath ---

test("resolveLearningPath: filters to one path, ordered, with prerequisite titles resolved", () => {
  const path = resolveLearningPath(ENTRIES, "intermediate");
  assert.equal(path.length, 1);
  assert.deepEqual(path[0].prerequisiteTitles, ["A"]);
});

test("resolveLearningPath: an id with no matching entry falls back to the raw id", () => {
  const withDangling = [...ENTRIES, { ...ENTRIES[2], id: "d", prerequisites: ["missing"] }];
  const path = resolveLearningPath(withDangling, "advanced");
  const d = path.find(e => e.id === "d");
  assert.deepEqual(d.prerequisiteTitles, ["missing"]);
});

test("LEARNING_PATHS includes the four required tiers", () => {
  for (const required of ["foundational", "intermediate", "advanced", "capstone"]) {
    assert.ok(LEARNING_PATHS.includes(required), `missing required path "${required}"`);
  }
});

test("CATEGORIES has a label and description for every category used in curriculum.json", () => {
  const { entries } = loadCurriculum();
  for (const entry of entries) {
    assert.ok(CATEGORIES[entry.category], `no CATEGORIES metadata for "${entry.category}" (used by "${entry.id}")`);
  }
});

// --- Canonical aggregate counts (docs/curriculum-manifest.md "Canonical
// terminology") — pinned here against the real manifest so documentation
// and assets/data/curriculum.json cannot silently diverge. If this test
// changes, every count in docs/performance-lab-inventory.md,
// docs/curriculum-manifest.md and lab/index.html's roadmap copy must be
// re-verified and updated in the same change.

const CANONICAL_COUNTS = {
  totalUniqueLaboratories: 41,
  referenceTier: 8,
  curriculumMembers: 36,
  referenceOnly: 5, // reference-tier AND NOT a curriculum member
  overlap: 3, // reference-tier AND a curriculum member (false-sharing, spsc-ring-buffer, jit-pipeline)
  curriculumOnly: 33, // curriculum member AND NOT reference-tier
  byStatus: { planned: 33, partial: 1, implemented: 7, verified: 0 },
};

test("canonical counts: total unique laboratory ids match curriculum.json length", () => {
  const { entries } = loadCurriculum();
  const ids = new Set(entries.map(e => e.id));
  assert.equal(ids.size, entries.length, "duplicate ids present — see validate-curriculum.js");
  assert.equal(entries.length, CANONICAL_COUNTS.totalUniqueLaboratories);
});

test("canonical counts: reference-tier vs. curriculum-member partition sums to the total with no double count", () => {
  const { entries } = loadCurriculum();
  const referenceTier = entries.filter(e => e.category === "reference");
  const curriculumMembers = entries.filter(e => e.curriculumMember === true);
  const referenceOnly = referenceTier.filter(e => !e.curriculumMember);
  const overlap = referenceTier.filter(e => e.curriculumMember);
  const curriculumOnly = curriculumMembers.filter(e => e.category !== "reference");

  assert.equal(referenceTier.length, CANONICAL_COUNTS.referenceTier);
  assert.equal(curriculumMembers.length, CANONICAL_COUNTS.curriculumMembers);
  assert.equal(referenceOnly.length, CANONICAL_COUNTS.referenceOnly);
  assert.equal(overlap.length, CANONICAL_COUNTS.overlap);
  assert.equal(curriculumOnly.length, CANONICAL_COUNTS.curriculumOnly);
  // Every unique laboratory is in exactly one of these three disjoint buckets.
  assert.equal(
    referenceOnly.length + overlap.length + curriculumOnly.length,
    entries.length,
  );
});

test("canonical counts: curriculumStatus distribution matches documented totals", () => {
  const { entries } = loadCurriculum();
  const byStatus = { planned: 0, partial: 0, implemented: 0, verified: 0 };
  for (const entry of entries) byStatus[entry.curriculumStatus]++;
  assert.deepEqual(byStatus, CANONICAL_COUNTS.byStatus);
});

test("canonical counts: every non-reference-category entry is a curriculum member (no silent exclusion)", () => {
  const { entries } = loadCurriculum();
  for (const entry of entries) {
    if (entry.category !== "reference") {
      assert.equal(entry.curriculumMember, true, `"${entry.id}" has a curriculum category but curriculumMember is not true`);
    }
  }
});

// --- mountCurriculum (DOM stub) ---

function createSelectStub(field, dynamic) {
  const listeners = {};
  return {
    dataset: { curriculumFilter: field },
    value: "",
    hasAttribute: name => (name === "data-curriculum-filter-dynamic" ? dynamic : false),
    append() {},
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
      if (sel === "[data-curriculum-registry]") return list;
      if (sel === "[data-curriculum-registry-empty]") return empty;
      if (sel === "[data-curriculum-filter-count]") return count;
      return null;
    },
    querySelectorAll(sel) {
      return sel === "[data-curriculum-filter]" ? selects : [];
    },
  };
}

async function mount() {
  const categorySelect = createSelectStub("category", true);
  const statusSelect = createSelectStub("curriculumStatus", true);
  const root = createRootStub([categorySelect, statusSelect]);
  globalThis.document = { createElement: () => ({ className: "", href: "", innerHTML: "" }) };
  globalThis.fetch = async () => ({ ok: true, json: async () => ENTRIES });
  await mountCurriculum(root);
  return { root, categorySelect, statusSelect };
}

test("curriculum roadmap: default state shows every entry, including planned ones with no route", async () => {
  const { root } = await mount();
  assert.equal(root.list.children.length, 3);
  assert.equal(root.empty.hidden, true);
  assert.equal(root.count.textContent, "Showing 3 of 3 laboratories");
});

test("curriculum roadmap: filtering by category narrows the list", async () => {
  const { root, categorySelect } = await mount();
  categorySelect.selectValue("jit-compiler");
  assert.equal(root.list.children.length, 1);
});

test("curriculum roadmap: filtering by curriculumStatus narrows the list", async () => {
  const { root, statusSelect } = await mount();
  statusSelect.selectValue("implemented");
  assert.equal(root.list.children.length, 1);
});

test("curriculum roadmap: a filter combination matching nothing shows the empty message", async () => {
  const { root, categorySelect, statusSelect } = await mount();
  categorySelect.selectValue("jit-compiler");
  statusSelect.selectValue("implemented"); // "c" is jit-compiler but planned — zero overlap
  assert.equal(root.list.children.length, 0);
  assert.equal(root.empty.hidden, false);
});
