#!/usr/bin/env node
// Static accessibility smoke checks (spec.md "Accessibility baseline").
// Not a substitute for axe/manual testing — catches the cheap, common regressions.
import { resolve } from "node:path";
import { findHtmlFiles, readFileSync } from "./lib/html.js";

const ROOT = resolve(import.meta.dirname, "..");
const errors = [];

function stripTags(text) {
  return text.replace(/<[^>]*>/g, "").trim();
}

for (const file of findHtmlFiles(ROOT)) {
  const rel = file.replace(`${ROOT}/`, "");
  const html = readFileSync(file, "utf8");

  // Every <img> needs an alt attribute (empty alt="" is valid for decorative images).
  for (const match of html.matchAll(/<img\b([^>]*)>/g)) {
    if (!/\balt\s*=/.test(match[1])) errors.push(`[${rel}] <img> missing alt attribute: ${match[0].slice(0, 80)}`);
  }

  // Every <button> needs accessible text: visible text content, aria-label, or aria-labelledby.
  for (const match of html.matchAll(/<button\b([^>]*)>([\s\S]*?)<\/button>/g)) {
    const [, attrs, inner] = match;
    const hasLabel = /\baria-label\s*=/.test(attrs) || /\baria-labelledby\s*=/.test(attrs);
    if (!hasLabel && stripTags(inner) === "") errors.push(`[${rel}] <button> has no accessible name: ${match[0].slice(0, 80)}`);
  }

  // tabindex must never be positive (breaks natural/logical focus order — docs/keyboard-rules.md).
  for (const match of html.matchAll(/\btabindex\s*=\s*"(-?\d+)"/g)) {
    if (Number(match[1]) > 0) errors.push(`[${rel}] positive tabindex="${match[1]}" breaks natural focus order`);
  }

  // Every form input needs a label: matching <label for>, aria-label, or aria-labelledby.
  const labelledFor = new Set([...html.matchAll(/<label\b[^>]*\bfor\s*=\s*"([^"]+)"/g)].map(m => m[1]));
  for (const match of html.matchAll(/<input\b([^>]*)>/g)) {
    const attrs = match[1];
    const typeMatch = attrs.match(/\btype\s*=\s*"([^"]+)"/);
    if (typeMatch && ["hidden", "submit", "button"].includes(typeMatch[1])) continue;
    const idMatch = attrs.match(/\bid\s*=\s*"([^"]+)"/);
    const hasLabel =
      /\baria-label\s*=/.test(attrs) ||
      /\baria-labelledby\s*=/.test(attrs) ||
      (idMatch && labelledFor.has(idMatch[1]));
    if (!hasLabel) errors.push(`[${rel}] <input> has no associated label: ${match[0].slice(0, 80)}`);
  }

  // Exactly one <h1> per page.
  const h1Count = [...html.matchAll(/<h1\b/g)].length;
  if (h1Count === 0) errors.push(`[${rel}] page has no <h1>`);
  if (h1Count > 1) errors.push(`[${rel}] page has ${h1Count} <h1> elements, expected exactly 1`);

  // <html lang="…"> must be present (already checked in validate-html.js, kept here for a standalone a11y pass).
  if (!/<html[^>]*\blang="[^"]+"/.test(html)) errors.push(`[${rel}] <html> is missing a non-empty lang attribute`);
}

if (errors.length > 0) {
  console.error(`check-accessibility: ${errors.length} problem(s) found\n`);
  for (const err of errors) console.error(`  - ${err}`);
  process.exit(1);
}

console.log("check-accessibility: OK");
