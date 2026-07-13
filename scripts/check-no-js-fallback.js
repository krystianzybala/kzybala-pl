#!/usr/bin/env node
// Confirms lab pages remain readable with JavaScript disabled (spec.md
// "Progressive enhancement": theory, code, and sources present in HTML).
// Since this only ever reads the raw HTML file bytes — never a rendered,
// script-executed DOM — it inherently sees exactly what a no-JS browser sees.
import { resolve, join } from "node:path";
import { findHtmlFiles, readFileSync } from "./lib/html.js";

const ROOT = resolve(import.meta.dirname, "..");
const LAB_DIR = join(ROOT, "lab");
const MIN_VISIBLE_TEXT_LENGTH = 300;

function visibleText(html) {
  return html
    .replace(/<script[\s\S]*?<\/script>/gi, "")
    .replace(/<style[\s\S]*?<\/style>/gi, "")
    .replace(/<!--[\s\S]*?-->/g, "")
    .replace(/<[^>]+>/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

// Extracts the raw inner HTML of each top-level role="tabpanel" element.
// Assumes tabpanels aren't nested inside one another (true for this site).
function extractTabpanels(html) {
  const panels = [];
  const openTag = /<([a-zA-Z][a-zA-Z0-9-]*)\b[^>]*\brole\s*=\s*"tabpanel"[^>]*>/g;
  let match;
  while ((match = openTag.exec(html))) {
    const tagName = match[1];
    const closeTag = new RegExp(`</${tagName}>`, "i");
    const rest = html.slice(openTag.lastIndex);
    const closeMatch = closeTag.exec(rest);
    if (closeMatch) panels.push(rest.slice(0, closeMatch.index));
  }
  return panels;
}

const errors = [];

for (const file of findHtmlFiles(LAB_DIR)) {
  const rel = file.replace(`${ROOT}/`, "");
  const html = readFileSync(file, "utf8");

  const text = visibleText(html);
  if (text.length < MIN_VISIBLE_TEXT_LENGTH) {
    errors.push(`[${rel}] only ${text.length} chars of visible text outside <script>/<style> — looks like a JS-only shell`);
  }

  for (const [i, panel] of extractTabpanels(html).entries()) {
    if (visibleText(panel).length === 0) errors.push(`[${rel}] tabpanel #${i + 1} has no static text content`);
  }
}

if (errors.length > 0) {
  console.error(`check-no-js-fallback: ${errors.length} problem(s) found\n`);
  for (const err of errors) console.error(`  - ${err}`);
  process.exit(1);
}

console.log("check-no-js-fallback: OK");
