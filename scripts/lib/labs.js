import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

const LABS_DIR = join(import.meta.dirname, "..", "..", "content", "labs");

export function listLabDirs() {
  return readdirSync(LABS_DIR)
    .filter(name => !name.startsWith("_") && !name.startsWith("."))
    .filter(name => statSync(join(LABS_DIR, name)).isDirectory())
    .sort();
}

// Returns { id -> { dir, path, raw, json, parseError } } for every non-template lab directory.
export function loadLabs() {
  const labs = new Map();
  for (const dir of listLabDirs()) {
    const path = join(LABS_DIR, dir, "lab.json");
    let raw = null;
    let json = null;
    let parseError = null;
    try {
      raw = readFileSync(path, "utf8");
    } catch {
      parseError = `missing lab.json (expected ${path})`;
    }
    if (raw !== null) {
      try {
        json = JSON.parse(raw);
      } catch (err) {
        parseError = `invalid JSON: ${err.message}`;
      }
    }
    labs.set(dir, { dir, path, json, parseError });
  }
  return labs;
}

export { LABS_DIR };
