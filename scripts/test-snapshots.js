#!/usr/bin/env node
// Structural snapshot testing: golden-file diffs of static markup, the same
// technique Jest/Vitest snapshots use for markup rather than pixels. This is
// a deliberately lightweight stand-in for full visual regression — pixel-level
// snapshotting (Percy/Playwright) would add a heavy browser-automation
// dependency this repo doesn't otherwise need; see docs/lab-framework.md.
//
// Usage: node scripts/test-snapshots.js [--update]
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";

const ROOT = resolve(import.meta.dirname, "..");
const SNAPSHOT_DIR = join(import.meta.dirname, "__snapshots__");
const UPDATE = process.argv.includes("--update");

const TARGETS = ["lab/index.html", "lab/false-sharing/index.html"];

function normalize(html) {
  return html
    .split("\n")
    .map(line => line.replace(/\s+$/, ""))
    .join("\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim() + "\n";
}

mkdirSync(SNAPSHOT_DIR, { recursive: true });

let failed = false;

for (const target of TARGETS) {
  const source = normalize(readFileSync(join(ROOT, target), "utf8"));
  const snapshotPath = join(SNAPSHOT_DIR, `${target.replace(/\//g, "__")}.snap`);

  if (UPDATE) {
    writeFileSync(snapshotPath, source);
    console.log(`test-snapshots: updated ${snapshotPath}`);
    continue;
  }

  if (!existsSync(snapshotPath)) {
    console.error(`test-snapshots: no snapshot for ${target} — run with --update to create one`);
    failed = true;
    continue;
  }

  const expected = readFileSync(snapshotPath, "utf8");
  if (expected !== source) {
    const expectedLines = expected.split("\n");
    const actualLines = source.split("\n");
    const firstDiff = expectedLines.findIndex((line, i) => line !== actualLines[i]);
    console.error(`test-snapshots: ${target} does not match its snapshot (first difference at line ${firstDiff + 1})`);
    console.error(`  expected: ${expectedLines[firstDiff]}`);
    console.error(`  actual:   ${actualLines[firstDiff]}`);
    console.error(`  If this change is intentional, run: node scripts/test-snapshots.js --update`);
    failed = true;
  }
}

if (failed) process.exit(1);
console.log(`test-snapshots: ${TARGETS.length} file(s) OK`);
