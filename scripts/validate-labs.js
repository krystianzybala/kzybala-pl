#!/usr/bin/env node
// Validates content/labs/*/lab.json against docs/lab-metadata-schema.md.
// Checks: metadata schema, duplicate IDs, prerequisite references, cycle
// detection, and required content files (spec.md "Standard learning
// sequence": theory, language write-ups, benchmark and sources must
// actually exist, not just be implied by lab.json flags).
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { loadLabs, LABS_DIR } from "./lib/labs.js";

const STATUS_VALUES = new Set(["draft", "stable", "deprecated"]);
const DIFFICULTY_VALUES = new Set(["beginner", "intermediate", "advanced"]);
const LANGUAGE_VALUES = new Set(["java", "rust"]);
const ID_PATTERN = /^[a-z0-9]+(-[a-z0-9]+)*$/;

const errors = [];
const fail = (dir, message) => errors.push(`[${dir}] ${message}`);

function isStringArray(value) {
  return Array.isArray(value) && value.every(v => typeof v === "string");
}

// --- 1. Metadata schema ---
function validateMetadata(dir, json) {
  const required = [
    "id", "title", "status", "level", "difficulty", "durationMinutes",
    "topics", "prerequisites", "unlocks", "languages",
    "interactive", "benchmark", "conceptualModel",
  ];
  for (const field of required) {
    if (!(field in json)) fail(dir, `missing required field "${field}"`);
  }

  if (typeof json.id === "string") {
    if (!ID_PATTERN.test(json.id)) fail(dir, `id "${json.id}" must be kebab-case`);
    if (json.id !== dir) fail(dir, `id "${json.id}" must equal directory name "${dir}"`);
  } else if ("id" in json) {
    fail(dir, `id must be a string`);
  }

  if ("title" in json && (typeof json.title !== "string" || json.title.trim() === "")) {
    fail(dir, `title must be a non-empty string`);
  }

  if ("status" in json && !STATUS_VALUES.has(json.status)) {
    fail(dir, `status "${json.status}" must be one of ${[...STATUS_VALUES].join(", ")}`);
  }

  if ("level" in json && (!Number.isInteger(json.level) || json.level < 1)) {
    fail(dir, `level must be an integer >= 1`);
  }

  if ("difficulty" in json && !DIFFICULTY_VALUES.has(json.difficulty)) {
    fail(dir, `difficulty "${json.difficulty}" must be one of ${[...DIFFICULTY_VALUES].join(", ")}`);
  }

  if ("durationMinutes" in json && (!Number.isInteger(json.durationMinutes) || json.durationMinutes <= 0)) {
    fail(dir, `durationMinutes must be an integer > 0`);
  }

  if ("topics" in json && (!isStringArray(json.topics) || json.topics.length === 0)) {
    fail(dir, `topics must be a non-empty array of strings`);
  }

  for (const field of ["prerequisites", "unlocks"]) {
    if (field in json && !isStringArray(json[field])) fail(dir, `${field} must be an array of strings`);
  }

  if ("languages" in json) {
    if (!isStringArray(json.languages)) {
      fail(dir, `languages must be an array of strings`);
    } else {
      for (const lang of json.languages) {
        if (!LANGUAGE_VALUES.has(lang)) fail(dir, `language "${lang}" must be one of ${[...LANGUAGE_VALUES].join(", ")}`);
      }
    }
  }

  for (const field of ["interactive", "benchmark", "conceptualModel"]) {
    if (field in json && typeof json[field] !== "boolean") fail(dir, `${field} must be a boolean`);
  }
}

const labs = loadLabs();

for (const [dir, lab] of labs) {
  if (lab.parseError) {
    fail(dir, lab.parseError);
    continue;
  }
  validateMetadata(dir, lab.json);
}

const validLabs = new Map([...labs].filter(([, lab]) => !lab.parseError));

// --- 2. Duplicate IDs ---
const idOwners = new Map();
for (const [dir, lab] of validLabs) {
  const id = lab.json.id;
  if (typeof id !== "string") continue;
  if (idOwners.has(id)) fail(dir, `duplicate id "${id}" also used by "${idOwners.get(id)}"`);
  else idOwners.set(id, dir);
}

// --- 3. Prerequisite / unlocks references ---
const knownIds = new Set(idOwners.keys());
for (const [dir, lab] of validLabs) {
  const { id, prerequisites = [], unlocks = [] } = lab.json;
  for (const prereq of prerequisites) {
    if (prereq === id) fail(dir, `lists itself as a prerequisite`);
    else if (!knownIds.has(prereq)) fail(dir, `prerequisite "${prereq}" does not exist`);
  }
  for (const unlock of unlocks) {
    if (unlock === id) fail(dir, `lists itself in unlocks`);
    else if (!knownIds.has(unlock)) fail(dir, `unlocks "${unlock}" does not exist`);
  }
}

// Bidirectional consistency: if A unlocks B, B must list A as a prerequisite.
for (const [dir, lab] of validLabs) {
  const { id, unlocks = [] } = lab.json;
  for (const unlock of unlocks) {
    const target = validLabs.get(unlock);
    if (target && !(target.json.prerequisites || []).includes(id)) {
      fail(dir, `unlocks "${unlock}" but "${unlock}" does not list "${id}" as a prerequisite`);
    }
  }
}

// --- 4. Cycle detection over the prerequisites graph ---
// Edge: prerequisite -> lab (prerequisite must come before lab).
const graph = new Map([...validLabs.keys()].map(dir => [validLabs.get(dir).json.id, []]));
for (const [, lab] of validLabs) {
  for (const prereq of lab.json.prerequisites || []) {
    if (graph.has(prereq)) graph.get(prereq).push(lab.json.id);
  }
}

const WHITE = 0, GRAY = 1, BLACK = 2;
const color = new Map([...graph.keys()].map(id => [id, WHITE]));

function detectCycle(id, stack) {
  color.set(id, GRAY);
  stack.push(id);
  for (const next of graph.get(id) || []) {
    if (color.get(next) === GRAY) {
      const cycleStart = stack.indexOf(next);
      errors.push(`[graph] cycle detected: ${stack.slice(cycleStart).concat(next).join(" -> ")}`);
      return true;
    }
    if (color.get(next) === WHITE && detectCycle(next, stack)) return true;
  }
  stack.pop();
  color.set(id, BLACK);
  return false;
}

for (const id of graph.keys()) {
  if (color.get(id) === WHITE) detectCycle(id, []);
}

// --- 5. Standard learning sequence: required content files actually exist ---
// content/labs/README.md's contract: theory.md and sources.md are always
// required; java.md/rust.md are required per lab.json#/languages;
// benchmark.md is required when lab.json#/benchmark is true.
for (const [dir, lab] of validLabs) {
  const { languages = [], benchmark } = lab.json;
  const required = ["theory.md", "sources.md"];
  if (languages.includes("java")) required.push("java.md");
  if (languages.includes("rust")) required.push("rust.md");
  if (benchmark) required.push("benchmark.md");
  for (const file of required) {
    if (!existsSync(join(LABS_DIR, dir, file))) {
      fail(dir, `missing required content file "${file}" (standard learning sequence)`);
    }
  }
}

// --- 6. Unified-framework content contract (plab-011-unified-lab-framework) ---
// Every lab must state its performance question and falsifiable hypothesis
// before results, ship diagnosis + implementation exercises with success
// criteria and collapsed hints/solutions, and (when it publishes benchmark
// data) show runnable reproduction commands and a raw-data section. A lab
// missing these fails the gate rather than silently rendering placeholders.
// Contract details: docs/lab-framework.md.
const read = (dir, file) => {
  const path = join(LABS_DIR, dir, file);
  return existsSync(path) ? readFileSync(path, "utf8") : null;
};

for (const [dir, lab] of validLabs) {
  const theory = read(dir, "theory.md");
  if (theory !== null) {
    if (!theory.includes("## Performance question and hypothesis")) {
      fail(dir, 'theory.md must open with a "## Performance question and hypothesis" section (unified framework contract)');
    } else {
      if (!/hypothesis/i.test(theory)) fail(dir, "theory.md must state a hypothesis");
      if (!/disprove|falsif/i.test(theory)) {
        fail(dir, "theory.md hypothesis must state what would disprove it (falsifiable conditions)");
      }
    }
  }

  const exercises = read(dir, "exercises.md");
  if (exercises === null) {
    fail(dir, 'missing required content file "exercises.md" (unified framework contract)');
  } else {
    if (!/#+ .*diagnosis/i.test(exercises)) fail(dir, "exercises.md must contain a diagnosis exercise");
    if (!/#+ .*implementation/i.test(exercises)) fail(dir, "exercises.md must contain an implementation exercise");
    if (!/success criteria/i.test(exercises)) fail(dir, "exercises.md exercises must state success criteria");
    if (!exercises.includes("<details>")) {
      fail(dir, "exercises.md must keep hints/solutions in collapsed <details> blocks, separate from the exercise statement");
    }
  }

  if (lab.json.benchmark) {
    const benchmark = read(dir, "benchmark.md");
    if (benchmark !== null) {
      if (!benchmark.includes("## Raw data and reproduction")) {
        fail(dir, 'benchmark.md must contain a "## Raw data and reproduction" section (raw evidence contract)');
      }
      if (!/```[\s\S]*?(mvn |cargo |java -jar|node scripts\/)[\s\S]*?```/.test(benchmark)) {
        fail(dir, "benchmark.md must show runnable reproduction commands in a fenced code block");
      }
    }
  }
}

if (errors.length > 0) {
  console.error(`validate-labs: ${errors.length} problem(s) found\n`);
  for (const err of errors) console.error(`  - ${err}`);
  process.exit(1);
}

console.log(`validate-labs: ${validLabs.size} lab(s) OK`);
