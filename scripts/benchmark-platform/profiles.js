import { readFileSync } from "node:fs";
import { join } from "node:path";

const PROFILES_PATH = join(
  import.meta.dirname,
  "..",
  "..",
  "content",
  "labs",
  "_shared",
  "benchmark-profiles.json",
);

const PROFILE_NAMES = ["smoke", "development", "full", "publication"];

// Returns { path, profiles, parseError }. `profiles` is null on parse failure.
function loadProfiles() {
  let raw = null;
  let parsed = null;
  let parseError = null;
  try {
    raw = readFileSync(PROFILES_PATH, "utf8");
  } catch {
    parseError = `missing benchmark-profiles.json (expected ${PROFILES_PATH})`;
  }
  if (raw !== null) {
    try {
      parsed = JSON.parse(raw);
    } catch (err) {
      parseError = `invalid JSON: ${err.message}`;
    }
  }
  return { path: PROFILES_PATH, profiles: parsed, parseError };
}

// Looks up a single profile by name. Throws on an unknown name so callers
// (CI steps, CLI scripts) fail loudly rather than silently benchmarking with
// the wrong rigor.
function resolveProfile(name) {
  const { profiles, parseError } = loadProfiles();
  if (parseError) {
    throw new Error(parseError);
  }
  const profile = profiles?.profiles?.[name];
  if (!profile) {
    const known = PROFILE_NAMES.join(", ");
    throw new Error(`unknown benchmark profile "${name}" (known profiles: ${known})`);
  }
  return profile;
}

// Renders a profile's JMH config as the CLI flags JMH's own runner accepts.
function jmhArgs(profile) {
  const { forks, warmupIterations, warmupTime, measurementIterations, measurementTime } = profile.jmh;
  return ["-f", String(forks), "-wi", String(warmupIterations), "-w", warmupTime, "-i", String(measurementIterations), "-r", measurementTime];
}

// Renders a profile's Criterion config as the CLI flags `cargo bench --bench <name> -- <flags>` accepts.
function criterionArgs(profile) {
  return [...profile.criterion.flags];
}

export { PROFILES_PATH, PROFILE_NAMES, loadProfiles, resolveProfile, jmhArgs, criterionArgs };
