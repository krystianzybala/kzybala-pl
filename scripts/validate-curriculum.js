#!/usr/bin/env node
// Validates assets/data/curriculum.json against docs/curriculum-manifest.md.
// Checks: schema/enums, duplicate ids, prerequisite references, cycle
// detection, and "no synthetic completion" (implemented/verified entries
// must have a real route and a real lab/<id>/index.html on disk).
import { existsSync } from "node:fs";
import { join } from "node:path";
import { loadCurriculum } from "./lib/curriculum.js";
import { LABS_DIR } from "./lib/labs.js";

const ROOT = join(import.meta.dirname, "..");

const CATEGORY_VALUES = new Set([
  "reference", "foundations", "jit-compiler", "memory-layout",
  "concurrency-lockfree", "io-systems", "systems-capstone",
]);
const PATH_VALUES = new Set(["foundational", "intermediate", "advanced", "capstone"]);
const STATUS_VALUES = new Set(["planned", "partial", "implemented", "verified"]);
const EVIDENCE_VALUES = new Set(["draft", "reproduced", "profiled", "verified"]);
const LANGUAGE_VALUES = new Set(["java", "rust"]);
const ID_PATTERN = /^[a-z0-9]+(-[a-z0-9]+)*$/;

const errors = [];
const fail = (id, message) => errors.push(`[${id}] ${message}`);

function isStringArray(value) {
  return Array.isArray(value) && value.every(v => typeof v === "string");
}

const { entries, parseError } = loadCurriculum();

if (parseError) {
  console.error(`validate-curriculum: ${parseError}`);
  process.exit(1);
}

if (!Array.isArray(entries)) {
  console.error("validate-curriculum: curriculum.json must be a JSON array");
  process.exit(1);
}

// --- 1. Schema ---
function validateEntry(entry) {
  const label = typeof entry.id === "string" ? entry.id : "<no id>";
  const required = [
    "id", "title", "category", "curriculumMember", "path", "level", "focusQuestion",
    "curriculumStatus", "evidenceMaturity", "languages", "durationMinutes",
    "prerequisites", "route", "sourceChange", "plannedChange",
  ];
  for (const field of required) {
    if (!(field in entry)) fail(label, `missing required field "${field}"`);
  }

  if (typeof entry.id === "string") {
    if (!ID_PATTERN.test(entry.id)) fail(label, `id "${entry.id}" must be kebab-case`);
  } else if ("id" in entry) {
    fail(label, "id must be a string");
  }

  if ("title" in entry && (typeof entry.title !== "string" || entry.title.trim() === "")) {
    fail(label, "title must be a non-empty string");
  }

  if ("category" in entry && !CATEGORY_VALUES.has(entry.category)) {
    fail(label, `category "${entry.category}" must be one of ${[...CATEGORY_VALUES].join(", ")}`);
  }

  if ("curriculumMember" in entry && typeof entry.curriculumMember !== "boolean") {
    fail(label, "curriculumMember must be a boolean");
  }

  // Every non-reference category is, by definition, part of the 36-lab
  // curriculum (docs/curriculum-manifest.md "Canonical terminology") — this
  // catches a new topical entry that forgot to set curriculumMember: true.
  if (entry.category && entry.category !== "reference" && entry.curriculumMember !== true) {
    fail(label, `category "${entry.category}" is a curriculum category, so curriculumMember must be true`);
  }

  if ("path" in entry && !PATH_VALUES.has(entry.path)) {
    fail(label, `path "${entry.path}" must be one of ${[...PATH_VALUES].join(", ")}`);
  }

  if ("level" in entry && (!Number.isInteger(entry.level) || entry.level < 1)) {
    fail(label, "level must be an integer >= 1");
  }

  if ("focusQuestion" in entry && (typeof entry.focusQuestion !== "string" || entry.focusQuestion.trim() === "")) {
    fail(label, "focusQuestion must be a non-empty string");
  }

  if ("curriculumStatus" in entry && !STATUS_VALUES.has(entry.curriculumStatus)) {
    fail(label, `curriculumStatus "${entry.curriculumStatus}" must be one of ${[...STATUS_VALUES].join(", ")}`);
  }

  if ("evidenceMaturity" in entry && entry.evidenceMaturity !== null && !EVIDENCE_VALUES.has(entry.evidenceMaturity)) {
    fail(label, `evidenceMaturity "${entry.evidenceMaturity}" must be null or one of ${[...EVIDENCE_VALUES].join(", ")}`);
  }

  if ("languages" in entry) {
    if (!isStringArray(entry.languages)) {
      fail(label, "languages must be an array of strings");
    } else {
      for (const lang of entry.languages) {
        if (!LANGUAGE_VALUES.has(lang)) fail(label, `language "${lang}" must be one of ${[...LANGUAGE_VALUES].join(", ")}`);
      }
    }
  }

  if ("durationMinutes" in entry && (!Number.isInteger(entry.durationMinutes) || entry.durationMinutes <= 0)) {
    fail(label, "durationMinutes must be an integer > 0");
  }

  if ("prerequisites" in entry && !isStringArray(entry.prerequisites)) {
    fail(label, "prerequisites must be an array of strings");
  }

  for (const field of ["route", "sourceChange", "plannedChange"]) {
    if (field in entry && entry[field] !== null && typeof entry[field] !== "string") {
      fail(label, `${field} must be null or a string`);
    }
  }
}

for (const entry of entries) validateEntry(entry);

// --- 2. Duplicate ids ---
const idOwners = new Map();
for (const entry of entries) {
  if (typeof entry.id !== "string") continue;
  if (idOwners.has(entry.id)) fail(entry.id, `duplicate id, first seen at index ${idOwners.get(entry.id)}`);
  else idOwners.set(entry.id, entries.indexOf(entry));
}

// --- 3. Prerequisite references ---
const knownIds = new Set(idOwners.keys());
for (const entry of entries) {
  const { id, prerequisites = [] } = entry;
  for (const prereq of prerequisites) {
    if (prereq === id) fail(id, "lists itself as a prerequisite");
    else if (!knownIds.has(prereq)) fail(id, `prerequisite "${prereq}" does not exist`);
  }
}

// --- 4. Cycle detection over the prerequisites graph ---
const graph = new Map(entries.filter(e => typeof e.id === "string").map(e => [e.id, e.prerequisites || []]));
const WHITE = 0, GRAY = 1, BLACK = 2;
const color = new Map([...graph.keys()].map(id => [id, WHITE]));

function detectCycle(id, stack) {
  color.set(id, GRAY);
  stack.push(id);
  for (const prereq of graph.get(id) || []) {
    if (!graph.has(prereq)) continue;
    if (color.get(prereq) === GRAY) {
      const cycleStart = stack.indexOf(prereq);
      errors.push(`[graph] cycle detected: ${stack.slice(cycleStart).concat(prereq).join(" -> ")}`);
      return true;
    }
    if (color.get(prereq) === WHITE && detectCycle(prereq, stack)) return true;
  }
  stack.pop();
  color.set(id, BLACK);
  return false;
}

for (const id of graph.keys()) {
  if (color.get(id) === WHITE) detectCycle(id, []);
}

// --- 5. No synthetic completion: implemented/verified must be real ---
for (const entry of entries) {
  if (entry.curriculumStatus !== "implemented" && entry.curriculumStatus !== "verified") continue;
  if (!entry.route) {
    fail(entry.id, `curriculumStatus is "${entry.curriculumStatus}" but route is null`);
    continue;
  }
  const htmlPath = join(ROOT, "lab", entry.id, "index.html");
  if (!existsSync(htmlPath)) {
    fail(entry.id, `curriculumStatus is "${entry.curriculumStatus}" but lab/${entry.id}/index.html does not exist`);
  }
  const contentDir = join(LABS_DIR, entry.id);
  if (!existsSync(contentDir)) {
    fail(entry.id, `curriculumStatus is "${entry.curriculumStatus}" but content/labs/${entry.id}/ does not exist`);
  }
}

// --- 6. planned/partial must not claim a live route ---
for (const entry of entries) {
  if ((entry.curriculumStatus === "planned" || entry.curriculumStatus === "partial") && entry.route) {
    fail(entry.id, `curriculumStatus is "${entry.curriculumStatus}" but route "${entry.route}" is set — a route means the lab is live`);
  }
}

if (errors.length > 0) {
  console.error(`validate-curriculum: ${errors.length} problem(s) found\n`);
  for (const err of errors) console.error(`  - ${err}`);
  process.exit(1);
}

console.log(`validate-curriculum: ${entries.length} curriculum entr${entries.length === 1 ? "y" : "ies"} OK`);
