#!/usr/bin/env node
// Builds sitemap.xml from the static page list below plus every stable lab
// in content/labs/*/lab.json — the single source for which lab URLs are
// public, so a newly shipped lab can never silently stay out of the
// sitemap. Run after validate-labs.js so this is only ever built from
// valid metadata. Mirrors generate-labs-index.js's approach.
import { writeFileSync } from "node:fs";
import { join } from "node:path";
import { loadLabs } from "./lib/labs.js";

const ROOT = join(import.meta.dirname, "..");
const BASE_URL = "https://kzybala.pl";

// Hand-maintained: every non-lab page that should be in the sitemap.
// Lab pages are derived from metadata below, not listed here.
const STATIC_PATHS = [
  "/",
  "/engineering.html",
  "/articles/",
  "/case-studies/",
  "/lab/",
  "/academy/",
  "/about/",
  "/research.html",
  "/talks.html",
  "/consulting.html",
  "/articles/zero-allocation-is-not-a-goal.html",
  "/articles/lock-free-can-be-slower.html",
  "/articles/jit-friendly-hot-paths.html",
  "/case-studies/parallel-payment-engine.html",
  "/case-studies/lock-free-infrastructure.html",
  "/case-studies/in-memory-decision-runtime.html",
];

const labs = [...loadLabs().values()].filter(lab => !lab.parseError && lab.json.status === "stable");
const labPaths = labs.map(lab => `/lab/${lab.json.id}/`).sort();

const paths = [...STATIC_PATHS, ...labPaths];
const body = paths.map(path => `  <url><loc>${BASE_URL}${path}</loc></url>`).join("\n");
const xml = `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${body}\n</urlset>\n`;

writeFileSync(join(ROOT, "sitemap.xml"), xml);
console.log(`generate-sitemap: wrote ${paths.length} url(s) (${labPaths.length} stable lab pages) to sitemap.xml`);
