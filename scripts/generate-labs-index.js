#!/usr/bin/env node
// Builds assets/data/labs-index.json from content/labs/*/lab.json — the only
// metadata the deployed site ever reads at runtime (assets/js/core/metadata.js).
// Run after validate-labs.js so the index is only ever built from valid metadata.
import { writeFileSync } from "node:fs";
import { join } from "node:path";
import { loadLabs } from "./lib/labs.js";

const OUT = join(import.meta.dirname, "..", "assets", "data", "labs-index.json");

const labs = [...loadLabs().values()]
  .filter(lab => !lab.parseError)
  .map(lab => lab.json)
  .sort((a, b) => a.level - b.level || a.id.localeCompare(b.id));

writeFileSync(OUT, `${JSON.stringify(labs, null, 2)}\n`);
console.log(`generate-labs-index: wrote ${labs.length} lab(s) to ${OUT}`);
