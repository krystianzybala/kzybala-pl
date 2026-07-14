#!/usr/bin/env node
// Prints a profile's JMH or Criterion CLI flags, space-separated, so shell
// callers (CI, local scripts) stay driven by content/labs/_shared/
// benchmark-profiles.json instead of hand-duplicating numbers that can
// drift from the documented profile.
import { resolveProfile, jmhArgs, criterionArgs } from "./profiles.js";

const [profileName, harness] = process.argv.slice(2);
if (!profileName || !["jmh", "criterion"].includes(harness)) {
  console.error("usage: node scripts/benchmark-platform/print-profile-args.js <profile-name> <jmh|criterion>");
  process.exit(2);
}

const profile = resolveProfile(profileName);
const args = harness === "jmh" ? jmhArgs(profile) : criterionArgs(profile);
console.log(args.join(" "));
