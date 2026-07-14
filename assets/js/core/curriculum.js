// Curriculum roadmap — renders and filters the full 36+ lab curriculum
// manifest (assets/data/curriculum.json) on /lab/, distinct from the
// lab-registry catalogue (assets/js/core/registry.js), which only lists
// labs that already have a content/labs/<id>/ directory. The curriculum
// roadmap is the only place a "planned" or "partial" lab is visible at all
// — spec requirement "No synthetic completion": a lab with no route stays
// visibly planned/partial here, it never gets a card in the registry.
let cache = null;

export async function loadCurriculumIndex() {
  if (cache) return cache;
  const res = await fetch("/assets/data/curriculum.json");
  if (!res.ok) throw new Error(`Failed to load curriculum index: ${res.status}`);
  cache = await res.json();
  return cache;
}

// Category metadata — the only place category labels/descriptions live, so
// the manifest itself only ever carries the category id (docs/design-tokens.md:
// no duplicated display strings scattered across data files).
export const CATEGORIES = {
  "reference": {
    label: "Reference Labs",
    description: "The flagship labs that prove the lab framework end to end, plus the existing cache/coherence/concurrency spine they build on.",
  },
  "foundations": {
    label: "Measurement Foundations",
    description: "How to benchmark honestly, and the CPU-level effects every later lab assumes you can already measure.",
  },
  "jit-compiler": {
    label: "JIT & Compiler Internals",
    description: "How HotSpot's tiered JIT turns bytecode into optimized machine code, and where that optimism breaks down.",
  },
  "memory-layout": {
    label: "Memory Layout & Off-Heap",
    description: "The real cost of object models, and how to control layout directly with off-heap memory, arenas and zero-copy parsing.",
  },
  "concurrency-lockfree": {
    label: "Concurrency & Lock-Free Engineering",
    description: "Atomics, contention, locks vs. lock-free algorithms, and shared-nothing thread-per-core partitioning.",
  },
  "io-systems": {
    label: "I/O, Serialization & Interop",
    description: "Turning bytes into state and back: binary formats, memory-mapped files, shared memory, and Java/Rust interop.",
  },
  "systems-capstone": {
    label: "Systems & Capstone",
    description: "End-to-end concurrency models, backpressure, NUMA/affinity, observability overhead, and a capstone combining the whole curriculum.",
  },
};

// Required learning paths (spec.md "Progressive learning paths"): at least
// foundational, intermediate, advanced and capstone, each with prerequisites.
export const LEARNING_PATHS = ["foundational", "intermediate", "advanced", "capstone"];

export const STATUS_BADGE = {
  planned: { label: "Planned", cls: "is-idle" },
  partial: { label: "Partial", cls: "is-warning" },
  implemented: { label: "Implemented", cls: "is-active" },
  verified: { label: "Verified", cls: "is-success" },
};

function byLevelThenId(a, b) {
  return (a.level ?? 0) - (b.level ?? 0) || a.id.localeCompare(b.id);
}

export function groupByCategory(entries) {
  const groups = new Map();
  for (const entry of entries) {
    if (!groups.has(entry.category)) groups.set(entry.category, []);
    groups.get(entry.category).push(entry);
  }
  for (const list of groups.values()) list.sort(byLevelThenId);
  return groups;
}

// Ordered labs for one learning path, each annotated with its prerequisite
// titles (not just ids) so a caller can render "next recommended labs...
// prerequisites are visible" (spec.md "Progressive learning paths") without
// a second lookup.
export function resolveLearningPath(entries, path) {
  const byId = new Map(entries.map(e => [e.id, e]));
  return entries
    .filter(e => e.path === path)
    .sort(byLevelThenId)
    .map(e => ({
      ...e,
      prerequisiteTitles: e.prerequisites.map(id => byId.get(id)?.title ?? id),
    }));
}

function applyFilters(entries, filters) {
  return entries.filter(e =>
    (!filters.category || e.category === filters.category) &&
    (!filters.level || String(e.level) === filters.level) &&
    (!filters.curriculumStatus || e.curriculumStatus === filters.curriculumStatus)
  );
}

function curriculumCard(entry) {
  const badge = STATUS_BADGE[entry.curriculumStatus] ?? STATUS_BADGE.planned;
  const el = document.createElement(entry.route ? "a" : "article");
  el.className = "card";
  if (entry.route) el.href = entry.route;
  const evidence = entry.evidenceMaturity ? `<p class="meta">Evidence: ${entry.evidenceMaturity}</p>` : "";
  el.innerHTML = `
    <span class="state-badge ${badge.cls}">${badge.label}</span>
    <h3>${entry.title}</h3>
    <p class="meta">${CATEGORIES[entry.category]?.label ?? entry.category} &middot; ${entry.path}</p>
    <p>${entry.focusQuestion}</p>
    ${evidence}
  `;
  return el;
}

export async function mountCurriculum(root) {
  const list = root.querySelector("[data-curriculum-registry]");
  if (!list) return;

  const entries = await loadCurriculumIndex();
  const filterInputs = [...root.querySelectorAll("[data-curriculum-filter]")];
  const empty = root.querySelector("[data-curriculum-registry-empty]");
  const count = root.querySelector("[data-curriculum-filter-count]");

  for (const input of filterInputs) {
    if (!input.hasAttribute("data-curriculum-filter-dynamic")) continue;
    const field = input.dataset.curriculumFilter;
    const source = field === "level" ? entries.map(e => String(e.level)) : entries.map(e => e[field]);
    const values = [...new Set(source)].sort();
    for (const value of values) {
      const option = document.createElement("option");
      option.value = value;
      option.textContent = field === "category"
        ? (CATEGORIES[value]?.label ?? value)
        : field === "curriculumStatus"
          ? (STATUS_BADGE[value]?.label ?? value)
          : value;
      input.append(option);
    }
  }

  const render = () => {
    const filters = Object.fromEntries(filterInputs.map(input => [input.dataset.curriculumFilter, input.value || null]));
    const filtered = applyFilters(entries, filters).sort(byLevelThenId);
    list.replaceChildren(...filtered.map(curriculumCard));
    if (empty) empty.hidden = filtered.length > 0;
    if (count) count.textContent = `Showing ${filtered.length} of ${entries.length} laboratories`;
  };

  filterInputs.forEach(input => input.addEventListener("change", render));
  render();
}
