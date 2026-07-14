import { execFileSync } from "node:child_process";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

const LABS_ROOT = join(import.meta.dirname, "..", "..", "content", "labs");

function javaDir(labId) {
  return join(LABS_ROOT, labId, "code", "java");
}

function rustDir(labId) {
  return join(LABS_ROOT, labId, "code", "rust");
}

function hasJavaTests(dir) {
  return existsSync(join(dir, "src", "test"));
}

// Rust has no separate test directory convention here — tests live inline as
// `#[test]` functions in src/lib.rs. Walk src/ looking for at least one.
function hasRustTests(dir) {
  const srcDir = join(dir, "src");
  if (!existsSync(srcDir)) return false;
  const stack = [srcDir];
  while (stack.length > 0) {
    const current = stack.pop();
    for (const entry of readdirSync(current, { withFileTypes: true })) {
      const path = join(current, entry.name);
      if (entry.isDirectory()) {
        stack.push(path);
      } else if (entry.name.endsWith(".rs") && readFileSync(path, "utf8").includes("#[test]")) {
        return true;
      }
    }
  }
  return false;
}

// Runs one language's correctness suite for a lab. Returns
// { status: "passed"|"failed"|"missing", detail }. "missing" is a real,
// reported gap — never silently treated as a pass (spec.md "Correctness
// before timing").
function runLanguageGate(dir, exists, hasTests, cmd, args) {
  if (!exists(dir)) {
    return { status: "not-applicable", detail: `no ${dir} directory for this lab` };
  }
  if (!hasTests(dir)) {
    return { status: "missing", detail: `${dir} has no correctness tests yet` };
  }
  try {
    const output = execFileSync(cmd, args, { cwd: dir, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
    return { status: "passed", detail: summarize(output) };
  } catch (err) {
    const raw = (err.stdout ?? "") + (err.stderr ?? "") || err.message || "";
    return { status: "failed", detail: raw.toString().trim().split("\n").slice(-30).join("\n") };
  }
}

// Keeps every "N tests ... ok/FAILED" summary line across all test targets
// (a multi-target `cargo test`/`mvn test` run prints one block per target,
// and a naive tail truncation can silently drop an earlier target's result).
function summarize(output) {
  const lines = output.trim().split("\n").filter((line) => /^(running \d+ tests?|test result:)/.test(line) || /Tests run:/.test(line));
  return lines.length > 0 ? lines.join("\n") : output.trim().split("\n").slice(-5).join("\n");
}

// The pre-benchmark correctness gate for one lab: runs the existing Java
// (mvn test) and Rust (cargo test) suites and reports pass/fail/missing per
// language. `overall` is "blocked" only on an actual failure — a language
// with no tests yet is a documented gap ("gap"), not grounds to fabricate a
// pass, but also not a hard failure of the gate itself.
function runCorrectnessGate(labId) {
  const java = runLanguageGate(javaDir(labId), existsSync, hasJavaTests, "mvn", ["test"]);
  const rust = runLanguageGate(rustDir(labId), existsSync, hasRustTests, "cargo", ["test", "--quiet"]);

  const statuses = [java.status, rust.status];
  let overall;
  if (statuses.includes("failed")) {
    overall = "blocked";
  } else if (statuses.includes("missing")) {
    overall = "gap";
  } else {
    overall = "pass";
  }

  return { labId, java, rust, overall };
}

export { runCorrectnessGate };
