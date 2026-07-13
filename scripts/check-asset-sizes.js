#!/usr/bin/env node
// Enforces the performance budgets in design.md (design.md "Performance budgets").
import { readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";

const ROOT = join(import.meta.dirname, "..");

const KB = 1024;
const BUDGETS = {
  perLabHtml: 80 * KB,
  sharedCss: 100 * KB,
  sharedJs: 120 * KB,
  perLabJs: 80 * KB,
};

function walk(dir) {
  let out = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) out = out.concat(walk(path));
    else out.push(path);
  }
  return out;
}

function size(path) {
  try {
    return statSync(path).size;
  } catch {
    return 0;
  }
}

const problems = [];
const report = [];

function check(label, bytes, budget) {
  const ok = bytes <= budget;
  report.push(`  ${ok ? "OK  " : "OVER"}  ${label}: ${(bytes / KB).toFixed(1)} KB / ${(budget / KB).toFixed(0)} KB`);
  if (!ok) problems.push(`${label} is ${(bytes / KB).toFixed(1)} KB, budget is ${(budget / KB).toFixed(0)} KB`);
}

// Shared CSS
check("assets/css/styles.css", size(join(ROOT, "assets/css/styles.css")), BUDGETS.sharedCss);

// Shared JS: main.js + everything under assets/js/core/
const coreDir = join(ROOT, "assets/js/core");
let sharedJsBytes = size(join(ROOT, "assets/js/main.js"));
try {
  sharedJsBytes += walk(coreDir).reduce((sum, f) => sum + size(f), 0);
} catch { /* core dir may be empty */ }
check("shared JS (main.js + assets/js/core/**)", sharedJsBytes, BUDGETS.sharedJs);

// Per-lab JS: each file under assets/js/labs/ individually
const labsJsDir = join(ROOT, "assets/js/labs");
try {
  for (const file of walk(labsJsDir)) {
    check(`assets/js/labs/${relative(labsJsDir, file)}`, size(file), BUDGETS.perLabJs);
  }
} catch { /* labs dir may be empty */ }

// Per-lab HTML: lab/index.html plus any lab/<id>/index.html
const labDir = join(ROOT, "lab");
try {
  for (const file of walk(labDir)) {
    if (file.endsWith(".html")) check(`lab/${relative(labDir, file)}`, size(file), BUDGETS.perLabHtml);
  }
} catch { /* lab dir may be empty */ }

console.log("check-asset-sizes:\n" + report.join("\n"));

if (problems.length > 0) {
  console.error(`\n${problems.length} budget(s) exceeded`);
  process.exit(1);
}
