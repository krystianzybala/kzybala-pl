// Builds the About page's roadmap timeline purely from lab metadata, plus
// one canonical file (assets/data/roadmap-planned.json) for ideas that
// don't have a lab.json yet. No status is ever hard-coded per lab here —
// every badge is derived from labs-index.json's `status` field, so a lab
// shipping can never leave a stale "planned"/"in progress" entry behind.
const STATUS_BADGE = {
  stable: { label: "Shipped", cls: "is-success" },
  draft: { label: "In progress", cls: "is-active" },
};

// Short descriptive blurbs are cosmetic only — never a source of status.
// A lab with no entry here still renders correctly, using its topics.
const BLURBS = {
  "cache-hierarchy": "The L1/L2/L3/RAM hierarchy, cache lines, spatial and temporal locality, and why sequential access lets hardware prefetching hide RAM latency.",
  "false-sharing": "Independent mutable data sharing one coherence unit, and why padding trades memory for eliminating cache-line ping-pong.",
  "mesi": "Coherence vs. consistency, the four-state MESI model, read-for-ownership, cache-to-cache transfer, and perf c2c methodology.",
  "memory-ordering": "Program order versus observed order, compiler and CPU reordering, happens-before, and data races — compared across VarHandles and Rust atomics.",
  "cas-contention": "Compare-and-set semantics, failed retries, contention collapse, exponential backoff, and why lock-free doesn't automatically mean low-latency.",
  "spsc-ring-buffer": "Bounded capacity, head/tail ownership, wrap-around, full/empty detection, cached cursors, and two concrete correctness bugs.",
  "thread-per-core": "Shared-nothing ownership, partitioning, affinity, handoff, backpressure, and predictable tail latency.",
};

function describeLab(lab) {
  return BLURBS[lab.id] ?? `${lab.topics.join(", ")} · ${lab.durationMinutes} min`;
}

function labItem(lab, badge) {
  return { id: lab.id, title: lab.title, description: describeLab(lab), badgeLabel: badge.label, badgeClass: badge.cls };
}

function byLevelThenId(a, b) {
  return (a.level ?? 0) - (b.level ?? 0) || a.id.localeCompare(b.id);
}

// `labs` — the parsed labs-index.json array. `plannedItems` — the parsed
// roadmap-planned.json array ({ title, description }[]), for ideas that
// exist only as a roadmap entry, not yet as a lab.
export function buildRoadmapItems(labs, plannedItems = []) {
  const shipped = labs
    .filter(lab => lab.status === "stable")
    .sort(byLevelThenId)
    .map(lab => labItem(lab, STATUS_BADGE.stable));

  const inProgress = labs
    .filter(lab => lab.status === "draft")
    .sort(byLevelThenId)
    .map(lab => labItem(lab, STATUS_BADGE.draft));

  const planned = plannedItems.map(item => ({
    id: null,
    title: item.title,
    description: item.description,
    badgeLabel: "Planned",
    badgeClass: "is-idle",
  }));

  return [...shipped, ...inProgress, ...planned];
}

export function renderRoadmap(container, items) {
  if (!container) return;
  container.replaceChildren(...items.map(item => {
    const article = document.createElement("article");
    article.className = "timeline-item";
    const badgeWrap = document.createElement("div");
    const badge = document.createElement("span");
    badge.className = `state-badge ${item.badgeClass}`;
    badge.textContent = item.badgeLabel;
    badgeWrap.append(badge);
    const body = document.createElement("div");
    const h3 = document.createElement("h3");
    h3.textContent = item.title;
    const p = document.createElement("p");
    p.textContent = item.description;
    body.append(h3, p);
    article.append(badgeWrap, body);
    return article;
  }));
}
