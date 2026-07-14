#!/usr/bin/env node
import { runCorrectnessGate } from "./correctness-gate.js";

const labId = process.argv[2];
if (!labId) {
  console.error("usage: node scripts/benchmark-platform/run-correctness-gate.js <lab-id>");
  process.exit(2);
}

const result = runCorrectnessGate(labId);
console.log(JSON.stringify(result, null, 2));

if (result.overall === "blocked") {
  console.error(`\nBLOCKED: correctness gate failed for "${labId}" — do not run benchmarks (spec.md "Correctness before timing").`);
  process.exit(1);
}
if (result.overall === "gap") {
  console.error(`\nGAP: "${labId}" has a language with no correctness tests yet. Benchmarks may proceed but are not gated by anything real for that language.`);
}
