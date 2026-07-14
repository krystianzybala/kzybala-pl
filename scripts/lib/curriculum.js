import { readFileSync } from "node:fs";
import { join } from "node:path";

const CURRICULUM_PATH = join(import.meta.dirname, "..", "..", "assets", "data", "curriculum.json");

// Returns { path, entries, parseError }. `entries` is null on parse failure.
export function loadCurriculum() {
  let raw = null;
  let entries = null;
  let parseError = null;
  try {
    raw = readFileSync(CURRICULUM_PATH, "utf8");
  } catch {
    parseError = `missing curriculum.json (expected ${CURRICULUM_PATH})`;
  }
  if (raw !== null) {
    try {
      entries = JSON.parse(raw);
    } catch (err) {
      parseError = `invalid JSON: ${err.message}`;
    }
  }
  return { path: CURRICULUM_PATH, entries, parseError };
}

export { CURRICULUM_PATH };
