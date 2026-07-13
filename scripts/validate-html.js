#!/usr/bin/env node
// HTML well-formedness + internal link validation (design.md "Testing").
import { existsSync, statSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { findHtmlFiles, checkTagBalance, extractLocalLinks, readFileSync } from "./lib/html.js";

const ROOT = resolve(import.meta.dirname, "..");
const errors = [];

function resolveLocalLink(target, fileDir) {
  const clean = target.split("#")[0].split("?")[0];
  if (clean === "") return null; // fragment/query-only, already excluded upstream but be safe
  const base = clean.startsWith("/") ? join(ROOT, clean) : join(fileDir, clean);
  const candidates = base.endsWith("/") ? [join(base, "index.html")] : [base, `${base}.html`, join(base, "index.html")];
  return candidates.some(c => existsSync(c) && statSync(c).isFile()) ? null : base;
}

const files = findHtmlFiles(ROOT);
for (const file of files) {
  const rel = file.replace(`${ROOT}/`, "");
  const html = readFileSync(file, "utf8");

  for (const err of checkTagBalance(html)) errors.push(`[${rel}] ${err}`);

  for (const link of extractLocalLinks(html)) {
    const missing = resolveLocalLink(link, dirname(file));
    if (missing) errors.push(`[${rel}] broken local link: "${link}"`);
  }

  if (!/<html[^>]*\blang="/.test(html)) errors.push(`[${rel}] <html> is missing a lang attribute`);
}

if (errors.length > 0) {
  console.error(`validate-html: ${errors.length} problem(s) found in ${files.length} file(s)\n`);
  for (const err of errors) console.error(`  - ${err}`);
  process.exit(1);
}

console.log(`validate-html: ${files.length} file(s) OK`);
