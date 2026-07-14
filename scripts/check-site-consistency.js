#!/usr/bin/env node
// Regression gate for cross-page consistency that no single existing check
// covers: every stable lab must actually be reachable, listed, and free of
// stale hard-coded status text. Run after generate-labs-index.js and
// generate-sitemap.js so it always checks against freshly built output.
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { loadLabs } from "./lib/labs.js";

const ROOT = join(import.meta.dirname, "..");
const errors = [];
const fail = msg => errors.push(msg);

const labs = [...loadLabs().values()].filter(lab => !lab.parseError);
const stableLabs = labs.filter(lab => lab.json.status === "stable");

// --- 1. Every stable lab is present in the built labs-index.json ---
const indexPath = join(ROOT, "assets", "data", "labs-index.json");
const index = JSON.parse(readFileSync(indexPath, "utf8"));
const indexIds = new Set(index.map(l => l.id));
for (const lab of stableLabs) {
  if (!indexIds.has(lab.json.id)) fail(`labs-index.json is missing stable lab "${lab.json.id}" — run npm run build:labs-index`);
}

// --- 2. Every stable lab's catalogue URL resolves to a real local HTML file ---
for (const lab of stableLabs) {
  const htmlPath = join(ROOT, "lab", lab.json.id, "index.html");
  if (!existsSync(htmlPath)) fail(`stable lab "${lab.json.id}" has no lab/${lab.json.id}/index.html — /lab/${lab.json.id}/ would 404`);
}

// --- 3. Every stable lab's URL is present in sitemap.xml ---
const sitemap = readFileSync(join(ROOT, "sitemap.xml"), "utf8");
for (const lab of stableLabs) {
  const loc = `https://kzybala.pl/lab/${lab.json.id}/`;
  if (!sitemap.includes(`<loc>${loc}</loc>`)) fail(`sitemap.xml is missing stable lab "${lab.json.id}" (${loc}) — run npm run build:sitemap`);
}

// --- 4. cache-hierarchy specifically: index, catalogue-reachable, sitemap (regression for a lab that has previously gone missing from one of these) ---
if (!stableLabs.some(lab => lab.json.id === "cache-hierarchy")) {
  fail(`cache-hierarchy is not a stable lab in content/labs/ — expected it to be present and stable`);
}

// --- 5. Homepage links directly to cache-hierarchy, not just the catalogue root ---
const homepage = readFileSync(join(ROOT, "index.html"), "utf8");
if (!homepage.includes('href="/lab/cache-hierarchy/"')) {
  fail(`index.html (homepage) does not link directly to /lab/cache-hierarchy/`);
}

// --- 6. About page's roadmap timeline must not contain a static, hard-coded status list ---
// (renderRoadmap() populates this container at runtime from labs-index.json;
// a static <article class="timeline-item"> here means someone reintroduced
// a duplicated, driftable status list.)
const about = readFileSync(join(ROOT, "about", "index.html"), "utf8");
const timelineMatch = about.match(/<div class="timeline" data-roadmap-timeline>([\s\S]*?)<\/div>\s*<\/section>/);
if (!timelineMatch) {
  fail(`about/index.html: could not find the [data-roadmap-timeline] container — has it been renamed or removed?`);
} else if (/class="timeline-item"/.test(timelineMatch[1])) {
  fail(`about/index.html: [data-roadmap-timeline] contains a static .timeline-item — roadmap status must be generated from metadata (assets/js/core/roadmap.js), not hard-coded`);
}

// --- 7. About page's automated-test counter must not reappear (unreliable to generate) ---
if (/automated (reducer )?tests?/i.test(about)) {
  fail(`about/index.html: an automated-test count reappeared in the signal strip — this cannot be generated reliably from metadata and must not be hard-coded (see task: remove the automated-test count)`);
}

// --- 8. Catalogue's "no results" message is hidden by default in the static markup ---
const catalogue = readFileSync(join(ROOT, "lab", "index.html"), "utf8");
if (!/data-lab-registry-empty\s+hidden(?:\s|>)/.test(catalogue)) {
  fail(`lab/index.html: [data-lab-registry-empty] must have the "hidden" attribute in the static markup so it never flashes visible before JS (or without JS) runs`);
}

// --- 9. Homepage must not link to the retired prototype anchors for labs that now have a full reference page ---
for (const [anchor, realId] of [["#false-sharing", "false-sharing"], ["#ring-buffer", "spsc-ring-buffer"]]) {
  if (homepage.includes(`/lab/${anchor}`) && stableLabs.some(lab => lab.json.id === realId)) {
    fail(`index.html (homepage) links to the retired prototype anchor /lab/${anchor}, but a full reference lab (${realId}) now exists — link to /lab/${realId}/ instead`);
  }
}

if (errors.length > 0) {
  console.error(`check-site-consistency: ${errors.length} problem(s) found\n`);
  for (const err of errors) console.error(`  - ${err}`);
  process.exit(1);
}

console.log(`check-site-consistency: OK (${stableLabs.length} stable lab(s) verified across index, catalogue, sitemap, homepage)`);
