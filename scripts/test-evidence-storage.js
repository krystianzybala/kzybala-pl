// Tests for the evidence storage/retention system
// (docs/evidence-storage-retention.md), added after the 2026-07 incident
// where results/ grew to ~131 GB and filled a 295 GB /home filesystem to
// zero headroom. Everything here uses FAKE filesystem statistics
// (PLAB_FAKE_FS_* hooks in lib/storage-lib.sh) and small fixture files —
// nothing in this file creates a large file or fills a real disk to test
// a "disk is full" or "artifact too big" path.
import test from "node:test";
import assert from "node:assert/strict";
import { execFileSync, execSync } from "node:child_process";
import {
  chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync, readdirSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join, relative } from "node:path";

const ROOT = join(import.meta.dirname, "..");
const STORAGE_LIB = join(ROOT, "scripts", "performance-lab", "lib", "storage-lib.sh");
const EVIDENCE_LIB = join(ROOT, "scripts", "performance-lab", "lib", "evidence-lib.sh");
const RUNNER = join(ROOT, "scripts", "performance-lab", "run-linux-evidence.sh");
const BATCH = join(ROOT, "scripts", "performance-lab", "run-all-benchmarks.sh");
const VERIFY_EVIDENCE = join(ROOT, "scripts", "performance-lab", "verify-evidence.sh");
const AUDIT = join(ROOT, "scripts", "performance-lab", "audit-evidence-storage.sh");
const CLEANUP = join(ROOT, "scripts", "performance-lab", "cleanup-evidence.sh");
const FIXTURES = join(ROOT, "scripts", "performance-lab", "__fixtures__");
const LSCPU_FIXTURE = join(FIXTURES, "lscpu-e-smt-two-sockets.txt");

function libCall(script, env = {}) {
  try {
    const stdout = execFileSync("bash", ["-c", `source '${STORAGE_LIB}'; ${script}`], {
      env: { ...process.env, ...env }, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"],
    });
    return { status: 0, stdout, stderr: "" };
  } catch (err) {
    return { status: err.status ?? 1, stdout: err.stdout?.toString() ?? "", stderr: err.stderr?.toString() ?? "" };
  }
}

function tmp(prefix) {
  return mkdtempSync(join(tmpdir(), prefix));
}

function rmAll(...paths) {
  for (const p of paths) rmSync(p, { recursive: true, force: true });
}

// LAB_JAVA_DIR is joined as "${REPO_ROOT}/${LAB_JAVA_DIR}" — escape the
// repo root with enough "../" segments so an OS-tmp-dir path resolves
// correctly (mirrors scripts/test-benchmark-batch.js's relFromRoot).
function relFromRoot(abs) {
  const rootParts = ROOT.split("/").filter(Boolean);
  return "../".repeat(rootParts.length) + abs.replace(/^\//, "");
}

// =============================================================================
// storage-lib.sh unit tests: filesystem stats (fake hook), budgets, raw-
// profiler retirement, bounded-text enforcement, classification.
// =============================================================================

test("fs_stats_json: the fake-filesystem test hook reports exactly the injected values, never real statvfs", () => {
  const r = libCall(`fs_stats_json /tmp`, {
    PLAB_FAKE_FS_TOTAL_BYTES: "1000000000000",
    PLAB_FAKE_FS_USED_BYTES: "999999000000",
    PLAB_FAKE_FS_AVAIL_BYTES: "1000000",
  });
  assert.equal(r.status, 0, r.stderr);
  const stats = JSON.parse(r.stdout);
  assert.equal(stats.availableBytes, 1000000);
  assert.equal(stats.totalBytes, 1000000000000);
  assert.match(stats.source, /fake/);
});

test("fs_stats_json: real statvfs reports availableBytes via f_bavail (never total-minus-used, which would count reserved blocks)", () => {
  const r = libCall(`fs_stats_json /tmp`);
  assert.equal(r.status, 0, r.stderr);
  const stats = JSON.parse(r.stdout);
  assert.ok(stats.availableBytes > 0);
  assert.match(stats.source, /statvfs/);
});

test("storage_preflight_check: rejects when available bytes are below the minimum, accepts otherwise", () => {
  const low = libCall(`storage_preflight_check /tmp 999999999999`, { PLAB_FAKE_FS_AVAIL_BYTES: "1000" });
  assert.notEqual(low.status, 0);
  assert.match(JSON.parse(low.stdout).withinBudget.toString(), /false/);
  const high = libCall(`storage_preflight_check /tmp 1000`, { PLAB_FAKE_FS_AVAIL_BYTES: "999999999999" });
  assert.equal(high.status, 0, high.stderr);
});

test("storage_budget_check: exact boundary is within budget, one byte over is not", () => {
  assert.equal(libCall(`storage_budget_check test 100 100`).status, 0);
  assert.notEqual(libCall(`storage_budget_check test 101 100`).status, 0);
});

test("dir_size_bytes: sums file sizes recursively and matches across nested directories", () => {
  const dir = tmp("plab-dirsize-");
  try {
    mkdirSync(join(dir, "a", "b"), { recursive: true });
    writeFileSync(join(dir, "a", "x.txt"), "12345");
    writeFileSync(join(dir, "a", "b", "y.txt"), "1234567890");
    const r = libCall(`dir_size_bytes '${dir}'`);
    assert.equal(Number(r.stdout.trim()), 15);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("retire_raw_profiler_file: refuses to delete before a validated (non-empty) summary exists", () => {
  const dir = tmp("plab-retire-");
  try {
    const raw = join(dir, "perf-c2c.data");
    const report = join(dir, "perf-c2c-report.txt");
    writeFileSync(raw, "binary-ish-content");
    // report does not exist yet
    const r = libCall(`retire_raw_profiler_file '${raw}' '${report}' 0 '${dir}/out.json'`);
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /refusing/);
    assert.equal(existsSync(raw), true, "raw file must survive when the summary is missing");
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("retire_raw_profiler_file: deletes the raw file by default once the summary exists, recording hash and size", () => {
  const dir = tmp("plab-retire-");
  try {
    const raw = join(dir, "perf-c2c.data");
    const report = join(dir, "perf-c2c-report.txt");
    writeFileSync(raw, "0123456789");
    writeFileSync(report, "# bounded summary\n");
    const r = libCall(`retire_raw_profiler_file '${raw}' '${report}' 0 '${dir}/out.json'`);
    assert.equal(r.status, 0, r.stderr);
    assert.equal(existsSync(raw), false, "raw file must be deleted by default");
    const out = JSON.parse(readFileSync(join(dir, "out.json"), "utf8"));
    assert.equal(out.rawProfilerRetained, false);
    assert.equal(out.rawProfilerOriginalBytes, 10);
    assert.equal(out.rawProfilerSha256.length, 64);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("retire_raw_profiler_file: explicit retention keeps the raw file on disk", () => {
  const dir = tmp("plab-retire-");
  try {
    const raw = join(dir, "perf-c2c.data");
    const report = join(dir, "perf-c2c-report.txt");
    writeFileSync(raw, "0123456789");
    writeFileSync(report, "# bounded summary\n");
    const r = libCall(`retire_raw_profiler_file '${raw}' '${report}' 1 '${dir}/out.json'`);
    assert.equal(r.status, 0, r.stderr);
    assert.equal(existsSync(raw), true, "raw file must survive with retain=1");
    const out = JSON.parse(readFileSync(join(dir, "out.json"), "utf8"));
    assert.equal(out.rawProfilerRetained, true);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("enforce_text_size_limit: rejects a report over the cap, accepts one at or under it", () => {
  const dir = tmp("plab-textcap-");
  try {
    const file = join(dir, "report.txt");
    writeFileSync(file, "x".repeat(101));
    assert.notEqual(libCall(`enforce_text_size_limit '${file}' 100`).status, 0);
    writeFileSync(file, "x".repeat(100));
    assert.equal(libCall(`enforce_text_size_limit '${file}' 100`).status, 0);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("classify_path: single source of truth agrees on canonical/raw-profiler/diagnostic/build-output/temporary", () => {
  const cases = {
    "environment.json": "canonical",
    "shared/jmh.json": "canonical",
    "shared/perf-c2c-report.txt": "canonical",
    "shared/perf-c2c.data": "raw-profiler",
    "shared/perf.data": "raw-profiler",
    "recording.jfr": "raw-profiler",
    "shared/timeout-jcmd-123-Thread.print.txt": "diagnostic",
    "false-sharing.console.log": "diagnostic",
    "code/java/target/benchmarks.jar": "build-output",
    "some.tmp": "temporary",
    "totally-unknown-file.xyz": "unclassified",
  };
  for (const [path, expected] of Object.entries(cases)) {
    const r = libCall(`classify_path '${path}'`);
    assert.equal(r.stdout.trim(), expected, `classify('${path}')`);
  }
});

// =============================================================================
// Profiler policy / profile behavior (dry-run — matches the existing
// "commands"/"dry-run" test style in test-linux-evidence.js: no perf/java
// stubs needed, the planned commands ARE the assertion).
// =============================================================================

function preflightStubDir() {
  const dir = tmp("plab-storage-stubs-");
  writeFileSync(join(dir, "uname"), "#!/bin/sh\necho Linux\n");
  writeFileSync(join(dir, "systemd-detect-virt"), "#!/bin/sh\necho none\nexit 1\n");
  writeFileSync(join(dir, "lscpu"), `#!/bin/sh\ncat '${LSCPU_FIXTURE}'\n`);
  writeFileSync(join(dir, "nproc"), "#!/bin/sh\necho 8\n");
  writeFileSync(join(dir, "perf"), `#!/bin/sh
if [ "$1" = "--version" ]; then echo "perf version 6.8"; exit 0; fi
if [ "$1" = "stat" ]; then exit 0; fi
if [ "$1" = "c2c" ]; then case "$*" in *"-e list"*) echo "ldlat-loads, ldlat-stores"; exit 0 ;; esac; fi
exit 0
`);
  writeFileSync(join(dir, "java"), "#!/bin/sh\necho 'openjdk version \"26\"'\nexit 0\n");
  writeFileSync(join(dir, "mvn"), "#!/bin/sh\nexit 0\n");
  for (const f of ["uname", "systemd-detect-virt", "lscpu", "nproc", "perf", "java", "mvn"]) chmodSync(join(dir, f), 0o755);
  return dir;
}

function runDry(lab, extraArgs, out) {
  const stubs = preflightStubDir();
  try {
    const stdout = execFileSync("bash", [RUNNER, lab, "--profile", ...extraArgs, "--cpus", labCpus(lab), "--dry-run", "--out", out], {
      env: { ...process.env, PATH: `${stubs}:/usr/bin:/bin` }, encoding: "utf8", cwd: ROOT, stdio: ["ignore", "pipe", "pipe"],
    });
    return { status: 0, stdout, stderr: "" };
  } catch (err) {
    return { status: err.status ?? 1, stdout: err.stdout?.toString() ?? "", stderr: err.stderr?.toString() ?? "" };
  } finally {
    rmSync(stubs, { recursive: true, force: true });
  }
}
function labCpus(lab) {
  return { "false-sharing": "0,1", mesi: "0,1", "spsc-ring-buffer": "0,1" }[lab];
}

test("profiler policy: smoke profile never plans perf c2c, even for a lab whose policy wants it", () => {
  const out = tmp("plab-storage-out-");
  try {
    const r = runDry("mesi", ["smoke"], out);
    assert.equal(r.status, 0, r.stderr);
    assert.doesNotMatch(r.stdout, /perf c2c record/, "smoke must never plan c2c");
  } finally {
    rmSync(out, { recursive: true, force: true });
  }
});

test("profiler policy: publication-sweep never plans perf c2c per matrix point, even for a lab whose policy wants it", () => {
  const out = tmp("plab-storage-out-");
  try {
    const r = runDry("mesi", ["publication-sweep"], out);
    assert.equal(r.status, 0, r.stderr);
    assert.doesNotMatch(r.stdout, /perf c2c record/, "publication-sweep must never plan c2c");
  } finally {
    rmSync(out, { recursive: true, force: true });
  }
});

test("profiler policy: publication-core plans c2c only for mesi's declared representative variants", () => {
  const out = tmp("plab-storage-out-");
  try {
    const r = runDry("mesi", ["publication-core"], out);
    assert.equal(r.status, 0, r.stderr);
    for (const variant of ["writerInvalidation", "pingPong", "paddedLines"]) {
      assert.match(r.stdout, new RegExp(`perf c2c record -o .*${variant}/perf-c2c\\.data`), `${variant} must plan c2c`);
    }
    for (const variant of ["singleWriter", "sharedReaders"]) {
      const lines = r.stdout.split("\n").filter((l) => l.includes(`/${variant}/`));
      assert.ok(lines.every((l) => !l.includes("perf c2c record")), `${variant} must NOT plan c2c`);
    }
  } finally {
    rmSync(out, { recursive: true, force: true });
  }
});

test("profiler policy: publication-core plans c2c only for spsc-ring-buffer's single representative case (cached-b1-c1024)", () => {
  const out = tmp("plab-storage-out-");
  try {
    const r = runDry("spsc-ring-buffer", ["publication-core"], out);
    assert.equal(r.status, 0, r.stderr);
    assert.match(r.stdout, /perf c2c record -o .*cached-b1-c1024\/perf-c2c\.data/);
    const otherLines = r.stdout.split("\n").filter((l) => l.includes("/uncached-b1-c1024/") || l.includes("/cached-b64-c1024/") || l.includes("/cached-b1-c65536/"));
    assert.ok(otherLines.every((l) => !l.includes("perf c2c record")), "only cached-b1-c1024 may plan c2c");
  } finally {
    rmSync(out, { recursive: true, force: true });
  }
});

test("profiler policy: false-sharing's both variants (shared, padded) ARE the representative set", () => {
  const out = tmp("plab-storage-out-");
  try {
    const r = runDry("false-sharing", ["publication-core"], out);
    assert.equal(r.status, 0, r.stderr);
    for (const variant of ["shared", "padded"]) {
      assert.match(r.stdout, new RegExp(`perf c2c record -o .*${variant}/perf-c2c\\.data`));
    }
  } finally {
    rmSync(out, { recursive: true, force: true });
  }
});

test("profiler policy: benchmark-profile.json records the resolved per-variant profiler policy", () => {
  const out = tmp("plab-storage-out-");
  try {
    const r = runDry("mesi", ["publication-core"], out);
    assert.equal(r.status, 0, r.stderr);
    const runDir = execFileSync("bash", ["-c", `find '${out}/mesi' -mindepth 1 -maxdepth 1 -type d`], { encoding: "utf8" }).trim();
    const profile = JSON.parse(readFileSync(join(runDir, "benchmark-profile.json"), "utf8"));
    assert.equal(profile.profilerPolicy.perVariant.writerInvalidation.c2c, true);
    assert.equal(profile.profilerPolicy.perVariant.singleWriter.c2c, false);
    assert.deepEqual(profile.profilerPolicy.c2cRepresentativeVariants.trim().split(/\s+/).sort(), ["paddedLines", "pingPong", "writerInvalidation"].sort());
  } finally {
    rmSync(out, { recursive: true, force: true });
  }
});

// =============================================================================
// Full non-dry-run runs against a synthetic lab (PLAB_LABS_DIR test hook):
// real filesystem-preflight, raw-profiler cleanup, per-variant size caps,
// bounded-report size caps and provenance — using fake filesystem stats and
// fully stubbed perf/java/mvn so nothing here spawns a real JVM or a real
// perf session.
// =============================================================================

function fullStubDir({ c2cReportFile }) {
  const dir = tmp("plab-full-stubs-");
  writeFileSync(join(dir, "uname"), "#!/bin/sh\necho Linux\n");
  writeFileSync(join(dir, "systemd-detect-virt"), "#!/bin/sh\necho none\nexit 1\n");
  writeFileSync(join(dir, "lscpu"), `#!/bin/sh\ncat '${LSCPU_FIXTURE}'\n`);
  writeFileSync(join(dir, "nproc"), "#!/bin/sh\necho 8\n");
  writeFileSync(join(dir, "numactl"), "#!/bin/sh\nexit 1\n");
  writeFileSync(join(dir, "taskset"), `#!/bin/sh
if [ "$1" = "-c" ]; then shift 2; exec "$@"; fi
exit 0
`);
  writeFileSync(join(dir, "java"), `#!/bin/sh
rff=""
prev=""
for a in "$@"; do
  if [ "$prev" = "-rff" ]; then rff="$a"; fi
  prev="$a"
done
if [ -n "$rff" ]; then
  echo '[{"benchmark":"fake.Bench","primaryMetric":{"score":1.0,"scoreUnit":"ns/op"}}]' > "$rff"
fi
echo 'openjdk version "26.0.1"'
exit 0
`);
  writeFileSync(join(dir, "mvn"), `#!/bin/sh
case "$*" in
  *package*) mkdir -p target && : > target/benchmarks.jar; exit 0 ;;
  *--version*) echo "Apache Maven 3.9"; exit 0 ;;
  *) exit 0 ;;
esac
`);
  writeFileSync(join(dir, "perf"), `#!/bin/sh
if [ "$1" = "--version" ]; then echo "perf version 6.8"; exit 0; fi
if [ "$1" = "stat" ]; then
  shift
  out=""
  while [ $# -gt 0 ]; do
    case "$1" in
      -o) out="$2"; shift 2 ;;
      --) shift; break ;;
      *) shift ;;
    esac
  done
  if [ -n "$out" ]; then
    {
      printf '0,,cpu-migrations,1000000,100.00,,\\n'
      printf '1000000,,task-clock,1000000,100.00,,\\n'
      printf '0,,context-switches,1000000,100.00,,\\n'
      printf '1000000,,cycles,1000000,100.00,,\\n'
    } > "$out"
  fi
  exec "$@"
fi
if [ "$1" = "c2c" ]; then
  shift
  # capability probe ("perf c2c record -e list") must be checked BEFORE
  # dispatching on the "record" subcommand below — it also starts with
  # "record", but takes no -o/-- and must never fall into the real-
  # recording branch (which would exec an empty command).
  case "$*" in *"-e list"*) echo "ldlat-loads, ldlat-stores"; exit 0 ;; esac
  case "$1" in
    record)
      shift
      out=""
      while [ $# -gt 0 ]; do
        case "$1" in
          -o) out="$2"; shift 2 ;;
          --) shift; break ;;
          *) shift ;;
        esac
      done
      if [ -n "$out" ]; then
        n="\${FAKE_C2C_BYTES:-4096}"
        head -c "$n" /dev/urandom > "$out" 2>/dev/null || perl -e "print 'x' x $n" > "$out"
      fi
      exec "$@"
      ;;
    report)
      cat "${c2cReportFile}"
      exit 0
      ;;
    *)
      exit 0
      ;;
  esac
fi
exit 0
`);
  for (const f of ["uname", "systemd-detect-virt", "lscpu", "nproc", "numactl", "taskset", "java", "mvn", "perf"]) {
    chmodSync(join(dir, f), 0o755);
  }
  return dir;
}

function makeSyntheticLab(policy, variants, c2cVariants) {
  const labsDir = tmp("plab-synth-labs-");
  const javaDir = tmp("plab-synth-java-");
  const labId = "storage-synthetic-lab";
  const conf = `#!/usr/bin/env bash
LAB_BENCHMARK_READY=1
LAB_CPUS_EXACT=2
LAB_MIN_CPUS=2
LAB_JAVA_DIR_ABS="${javaDir}"
LAB_JAVA_DIR="\${LAB_JAVA_DIR_ABS#\${REPO_ROOT}/}"
EV_SELECTOR="fake.Selector"
LAB_C2C_REQUIRED=0
LAB_PROFILER_POLICY="${policy}"
lab_variants() { echo "${variants.join(" ")}"; }
${c2cVariants ? `lab_c2c_variants() { echo "${c2cVariants.join(" ")}"; }` : ""}
lab_jmh_args() { echo ""; }
lab_threads() { echo 1; }
lab_cpu_count() { echo 1; }
lab_worker_props() { echo ""; }
lab_rust_evidence_cmd() { echo ""; }
`;
  writeFileSync(join(labsDir, `${labId}.conf`), conf);
  return { labsDir, javaDir, labId };
}

// LAB_JAVA_DIR is resolved by the runner as ${REPO_ROOT}/${LAB_JAVA_DIR} —
// since our synthetic java dir is an absolute tmp path outside the repo,
// re-derive LAB_JAVA_DIR as a relative-with-".." path from ROOT so joining
// it back to REPO_ROOT resolves to the real (tmp) javaDir.
function fixLabJavaDir(labsDir, labId, javaDir) {
  const rel = relative(ROOT, javaDir);
  let conf = readFileSync(join(labsDir, `${labId}.conf`), "utf8");
  conf = conf.replace(/LAB_JAVA_DIR_ABS=.*\n/, "").replace(/LAB_JAVA_DIR=.*\n/, `LAB_JAVA_DIR="${rel}"\n`);
  writeFileSync(join(labsDir, `${labId}.conf`), conf);
}

function runFull(labSpec, args, env, out, c2cReportFile) {
  fixLabJavaDir(labSpec.labsDir, labSpec.labId, labSpec.javaDir);
  const stubs = fullStubDir({ c2cReportFile: c2cReportFile ?? join(labSpec.javaDir, "empty-report.txt") });
  if (!existsSync(join(labSpec.javaDir, "empty-report.txt"))) writeFileSync(join(labSpec.javaDir, "empty-report.txt"), "# empty c2c report\nTotal records: 0\n");
  // Default to profile=development unless the caller names one explicitly.
  // This is a real (non-dry-run) measurement invocation against the real
  // repo checkout (cwd=ROOT): publication-core/sweep/full now hard-abort
  // on a dirty tracked source tree before measurement (the provenance
  // invariant these fixtures have nothing to do with), and this repo is
  // routinely dirty while its own tests are being developed/run.
  const finalArgs = args.includes("--profile") ? args : ["--profile", "development", ...args];
  try {
    const stdout = execFileSync("bash", [RUNNER, labSpec.labId, "--cpus", "0,1", "--skip-load-check", "--out", out, ...finalArgs], {
      env: { ...process.env, PATH: `${stubs}:/usr/bin:/bin`, PLAB_LABS_DIR: labSpec.labsDir, ...env },
      encoding: "utf8", cwd: ROOT, stdio: ["ignore", "pipe", "pipe"],
    });
    return { status: 0, stdout, stderr: "" };
  } catch (err) {
    return { status: err.status ?? 1, stdout: err.stdout?.toString() ?? "", stderr: err.stderr?.toString() ?? "" };
  } finally {
    rmSync(stubs, { recursive: true, force: true });
  }
}

function findRunDir(out, labId) {
  return execFileSync("bash", ["-c", `find '${out}/${labId}' -mindepth 1 -maxdepth 1 -type d`], { encoding: "utf8" }).trim();
}

test("storage preflight: rejects a run when available space is below the minimum, before any build or measurement", () => {
  const lab = makeSyntheticLab("stat", ["v1"], null);
  const out = tmp("plab-full-out-");
  try {
    const r = runFull(lab, [], { PLAB_FAKE_FS_TOTAL_BYTES: "1000000000000", PLAB_FAKE_FS_AVAIL_BYTES: "1000" }, out);
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /failed-storage-preflight/);
    const runDir = findRunDir(out, lab.labId);
    const status = JSON.parse(readFileSync(join(runDir, "run-status.json"), "utf8"));
    assert.equal(status.runStatus, "rejected");
    assert.equal(status.rejectionReason, "failed-storage-preflight");
    // never reached the correctness gate — no correctness-console.log
    assert.equal(existsSync(join(runDir, "correctness-console.log")), false);
  } finally {
    rmAll(lab.labsDir, lab.javaDir, out);
  }
});

test("raw-profiler cleanup: a successful c2c summary deletes the raw perf-c2c.data by default", () => {
  const lab = makeSyntheticLab("c2c", ["v1"], null);
  const reportFile = join(lab.javaDir, "report.txt");
  writeFileSync(reportFile, "Total records processed: 10\nShared Data Cache Line Table\nHITM local 1 remote 0\n");
  const out = tmp("plab-full-out-");
  try {
    const r = runFull(lab, [], {}, out, reportFile);
    assert.equal(r.status, 0, r.stderr);
    const runDir = findRunDir(out, lab.labId);
    assert.equal(existsSync(join(runDir, "v1", "perf-c2c.data")), false, "raw perf-c2c.data must be deleted by default");
    assert.equal(existsSync(join(runDir, "v1", "perf-c2c-report.txt")), true, "the bounded summary report must survive");
    const retention = JSON.parse(readFileSync(join(runDir, "v1", "raw-profiler-retention.json"), "utf8"));
    assert.equal(retention.rawProfilerRetained, false);
    assert.equal(retention.rawProfilerSha256.length, 64);
  } finally {
    rmAll(lab.labsDir, lab.javaDir, out);
  }
});

test("raw-profiler cleanup: --retain-raw-profiler-data keeps the raw file on disk", () => {
  const lab = makeSyntheticLab("c2c", ["v1"], null);
  const reportFile = join(lab.javaDir, "report.txt");
  writeFileSync(reportFile, "Total records processed: 10\n");
  const out = tmp("plab-full-out-");
  try {
    const r = runFull(lab, ["--retain-raw-profiler-data"], {}, out, reportFile);
    assert.equal(r.status, 0, r.stderr);
    const runDir = findRunDir(out, lab.labId);
    assert.equal(existsSync(join(runDir, "v1", "perf-c2c.data")), true, "raw file must survive with --retain-raw-profiler-data");
    const retention = JSON.parse(readFileSync(join(runDir, "v1", "raw-profiler-retention.json"), "utf8"));
    assert.equal(retention.rawProfilerRetained, true);
  } finally {
    rmAll(lab.labsDir, lab.javaDir, out);
  }
});

test("archive policy: the default archive excludes raw profiler data even when it was retained on disk", () => {
  const lab = makeSyntheticLab("c2c", ["v1"], null);
  const reportFile = join(lab.javaDir, "report.txt");
  writeFileSync(reportFile, "Total records processed: 10\n");
  const out = tmp("plab-full-out-");
  try {
    const r = runFull(lab, ["--retain-raw-profiler-data"], {}, out, reportFile);
    assert.equal(r.status, 0, r.stderr);
    const archive = execFileSync("bash", ["-c", `ls '${out}'/${lab.labId}-*-linux-evidence.tar.* 2>/dev/null | head -1`], { encoding: "utf8" }).trim();
    assert.ok(archive, "archive must exist");
    const listing = execFileSync("bash", ["-c", `tar -tf '${archive}'`], { encoding: "utf8" });
    assert.doesNotMatch(listing, /perf-c2c\.data/, "the default archive must never contain the retained raw file");
    assert.match(listing, /perf-c2c-report\.txt/, "the bounded summary report must be archived");
    const rawArchive = execFileSync("bash", ["-c", `ls '${out}'/${lab.labId}-*-raw-profiler.tar.* 2>/dev/null | head -1`], { encoding: "utf8" }).trim();
    assert.ok(rawArchive, "a separate raw-profiler archive must exist when explicitly requested");
    const rawListing = execFileSync("bash", ["-c", `tar -tf '${rawArchive}'`], { encoding: "utf8" });
    assert.match(rawListing, /perf-c2c\.data/, "the separate raw archive must contain the retained raw file");
  } finally {
    rmAll(lab.labsDir, lab.javaDir, out);
  }
});

test("per-variant raw-profiler cap: an oversized perf-c2c.data is rejected (failed-artifact-size-limit) before the report is even generated", () => {
  const lab = makeSyntheticLab("c2c", ["v1"], null);
  const out = tmp("plab-full-out-");
  try {
    const r = runFull(lab, [], { FAKE_C2C_BYTES: "4096", PLAB_MAX_RAW_PROFILER_BYTES_PER_VARIANT: "100" }, out);
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /failed-artifact-size-limit/);
    const runDir = findRunDir(out, lab.labId);
    assert.equal(existsSync(join(runDir, "v1", "perf-c2c-report.txt")), false, "the report must never be generated for a rejected oversized recording");
    const status = JSON.parse(readFileSync(join(runDir, "run-status.json"), "utf8"));
    assert.equal(status.runStatus, "rejected");
  } finally {
    rmAll(lab.labsDir, lab.javaDir, out);
  }
});

test("bounded text report cap: a report that still exceeds the configured maximum is rejected deterministically", () => {
  const lab = makeSyntheticLab("c2c", ["v1"], null);
  const reportFile = join(lab.javaDir, "report.txt");
  writeFileSync(reportFile, "Total records processed: 10\n" + "x".repeat(500));
  const out = tmp("plab-full-out-");
  try {
    const r = runFull(lab, [], { PLAB_MAX_TEXT_REPORT_BYTES: "50" }, out, reportFile);
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /failed-artifact-size-limit/);
  } finally {
    rmAll(lab.labsDir, lab.javaDir, out);
  }
});

test("failed runs: raw profiler recordings are deleted by default when a later step in the same variant rejects the run", () => {
  // A worker-placement violation after a real c2c recording rejects the
  // whole run; the raw-profiler cleanup path (mark_rejected) must still
  // fire even though the rejection reason is unrelated to storage.
  const lab = makeSyntheticLab("stat", ["v1"], null);
  const out = tmp("plab-full-out-");
  try {
    const r = runFull(lab, [], {}, out);
    // stat-only lab: no c2c ever produced, so nothing to clean up — this
    // documents the "policy=stat means no raw-profiler file exists at all"
    // baseline the c2c-specific tests above contrast against.
    assert.equal(r.status, 0, r.stderr);
    const runDir = findRunDir(out, lab.labId);
    assert.equal(existsSync(join(runDir, "v1", "perf-c2c.data")), false);
  } finally {
    rmAll(lab.labsDir, lab.javaDir, out);
  }
});

test("smoke budget: a smoke run is rejected when it exceeds its configured total-bytes budget", () => {
  const lab = makeSyntheticLab("stat", ["v1", "v2"], null);
  const out = tmp("plab-full-out-");
  try {
    const r = runFull(lab, ["--profile", "smoke"], { PLAB_SMOKE_BUDGET_BYTES: "10" }, out);
    assert.notEqual(r.status, 0);
    const runDir = findRunDir(out, lab.labId);
    const status = JSON.parse(readFileSync(join(runDir, "run-status.json"), "utf8"));
    assert.equal(status.runStatus, "rejected");
    assert.match(status.rejectionReason, /failed-storage-budget/);
  } finally {
    rmAll(lab.labsDir, lab.javaDir, out);
  }
});

test("smoke budget: a smoke run well under its budget collects normally", () => {
  const lab = makeSyntheticLab("stat", ["v1"], null);
  const out = tmp("plab-full-out-");
  try {
    const r = runFull(lab, ["--profile", "smoke"], {}, out);
    assert.equal(r.status, 0, r.stderr);
    const runDir = findRunDir(out, lab.labId);
    const status = JSON.parse(readFileSync(join(runDir, "run-status.json"), "utf8"));
    assert.equal(status.runStatus, "collected");
    const budget = JSON.parse(readFileSync(join(runDir, "smoke-budget.json"), "utf8"));
    assert.equal(budget.withinBudget, true);
  } finally {
    rmAll(lab.labsDir, lab.javaDir, out);
  }
});

test("run-total budget: a run whose total bytes exceed its configured budget aborts with a storage state (exit 4), no orphan processes", () => {
  const lab = makeSyntheticLab("stat", ["v1", "v2", "v3"], null);
  const out = tmp("plab-full-out-");
  try {
    // A tiny MAX_RUN_BYTES makes the "after variant" check trip once the
    // first variant's own evidence files exist — every prior measurement
    // command has already completed normally (this design checks storage
    // BETWEEN measurements, never mid-invocation), so this exercises the
    // full mark_storage_abort path end-to-end: partial manifest, raw-
    // profiler cleanup call, SHA256SUMS, exit 4 — with no process left
    // behind because none was ever left running past its own completion.
    const r = runFull(lab, [], { PLAB_MAX_BATCH_BYTES: "50" }, out);
    assert.equal(r.status, 4, r.stderr);
    assert.match(r.stderr, /STORAGE ABORT/);
    const runDir = findRunDir(out, lab.labId);
    const status = JSON.parse(readFileSync(join(runDir, "run-status.json"), "utf8"));
    assert.match(status.runStatus, /failed-storage-budget/);
    assert.equal(existsSync(join(runDir, "SHA256SUMS")), true, "a bounded diagnostic manifest (including hashes) is still preserved");
  } finally {
    rmAll(lab.labsDir, lab.javaDir, out);
  }
});

test("provenance: environment.json and the manifest record the output root, storage-target, and whether it shares the repository's filesystem", () => {
  const out = tmp("plab-full-out-");
  try {
    const stubs = preflightStubDir();
    try {
      execFileSync("bash", [RUNNER, "mesi", "--profile", "publication-core", "--cpus", "0,1", "--preflight-only", "--out", out], {
        env: { ...process.env, PATH: `${stubs}:/usr/bin:/bin` }, cwd: ROOT, stdio: ["ignore", "pipe", "pipe"],
      });
    } finally {
      rmSync(stubs, { recursive: true, force: true });
    }
    const runDir = findRunDir(out, "mesi");
    const env = JSON.parse(readFileSync(join(runDir, "environment.json"), "utf8"));
    assert.equal(env.outputRoot, out);
    assert.equal(env.storageBudgetTarget, out, "defaults to --out when --storage-target is not given");
    assert.equal(typeof env.storage.resultsFilesystem.availableBytes, "number");
    assert.equal(typeof env.storage.sameFilesystemAsRepo, "boolean");
    assert.equal(env.storage.sameFilesystemAsRepo, true, "both are under the OS tmp dir in this test");
  } finally {
    rmSync(out, { recursive: true, force: true });
  }
});

// =============================================================================
// audit-evidence-storage.sh
// =============================================================================

test("audit: reports total/per-class/per-lab/per-variant bytes and the top-N largest files without creating a large file itself", () => {
  const root = tmp("plab-audit-");
  try {
    const runDir = join(root, "lab-x", "linux-20260101T000000Z", "variant-a");
    mkdirSync(runDir, { recursive: true });
    writeFileSync(join(runDir, "..", "environment.json"), "{}");
    writeFileSync(join(runDir, "jmh.json"), "{}");
    writeFileSync(join(runDir, "perf-c2c.data"), "x".repeat(5000));
    const r = execFileSync("bash", [AUDIT, root, "--json"], { encoding: "utf8" });
    const report = JSON.parse(r);
    assert.equal(report.totalBytes, 5004);
    assert.equal(report.byClass["raw-profiler"], 5000);
    assert.ok(report.perLab["lab-x"] >= 5000);
    assert.ok(Object.keys(report.perVariant).some((k) => k === "lab-x/variant-a"));
    assert.ok(report.top50Files.length > 0);
    // the report itself must stay small — bounded output, not one line per file
    assert.ok(r.length < 20000);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

// =============================================================================
// cleanup-evidence.sh
// =============================================================================

function cleanupFixture() {
  const root = tmp("plab-cleanup-");
  const goodRun = join(root, "lab-x", "linux-20260101T000000Z");
  mkdirSync(join(goodRun, "v1"), { recursive: true });
  writeFileSync(join(goodRun, "environment.json"), "{}");
  writeFileSync(join(goodRun, "run-status.json"), JSON.stringify({ runStatus: "collected" }));
  writeFileSync(join(goodRun, "v1", "perf-c2c.data"), "raw-good-run");
  writeFileSync(join(goodRun, "v1", "perf-c2c-report.txt"), "# summary");

  mkdirSync(join(root, "batches", "batch-20260102T000000Z", "run-1", "lab-x", "linux-20260102T000000Z", "v1"), { recursive: true });
  const partialBatch = join(root, "batches", "batch-20260102T000000Z");
  writeFileSync(join(partialBatch, "batch-manifest.json"), JSON.stringify({ state: "partial" }));
  writeFileSync(join(partialBatch, "run-1", "lab-x", "linux-20260102T000000Z", "v1", "perf-c2c.data"), "raw-partial-batch");
  writeFileSync(join(partialBatch, "run-1", "lab-x", "linux-20260102T000000Z", "run-status.json"), JSON.stringify({ runStatus: "rejected" }));
  return root;
}

test("cleanup: defaults to --dry-run — nothing is deleted without --apply", () => {
  const root = cleanupFixture();
  try {
    const before = readdirSync(join(root, "lab-x", "linux-20260101T000000Z", "v1"));
    execFileSync("bash", [CLEANUP, "--results-root", root, "--policy", "raw-profiler-only"]);
    const after = readdirSync(join(root, "lab-x", "linux-20260101T000000Z", "v1"));
    assert.deepEqual(after.sort(), before.sort(), "dry-run must not modify the tree");
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("cleanup: raw-profiler-only removes every raw-profiler file everywhere, never the canonical bounded report", () => {
  const root = cleanupFixture();
  try {
    const out = execFileSync("bash", [CLEANUP, "--results-root", root, "--policy", "raw-profiler-only", "--apply", "--json"], { encoding: "utf8" });
    const plan = JSON.parse(out);
    assert.equal(plan.fileCount, 2);
    assert.equal(existsSync(join(root, "lab-x", "linux-20260101T000000Z", "v1", "perf-c2c.data")), false);
    assert.equal(existsSync(join(root, "batches", "batch-20260102T000000Z", "run-1", "lab-x", "linux-20260102T000000Z", "v1", "perf-c2c.data")), false);
    assert.equal(existsSync(join(root, "lab-x", "linux-20260101T000000Z", "v1", "perf-c2c-report.txt")), true, "canonical report must survive");
    assert.equal(existsSync(join(root, "lab-x", "linux-20260101T000000Z", "environment.json")), true, "canonical environment.json must survive");
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("cleanup: failed-and-partial only touches the partial batch's run, never the collected standalone run", () => {
  const root = cleanupFixture();
  try {
    const out = execFileSync("bash", [CLEANUP, "--results-root", root, "--policy", "failed-and-partial", "--apply", "--json"], { encoding: "utf8" });
    const plan = JSON.parse(out);
    assert.equal(plan.fileCount, 1);
    assert.equal(existsSync(join(root, "lab-x", "linux-20260101T000000Z", "v1", "perf-c2c.data")), true, "the collected standalone run must be untouched");
    assert.equal(existsSync(join(root, "batches", "batch-20260102T000000Z", "run-1", "lab-x", "linux-20260102T000000Z", "v1", "perf-c2c.data")), false);
    assert.deepEqual(plan.batchesAffected, ["batch-20260102T000000Z"]);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("cleanup: never selects a canonical file even under keep-canonical, and reports how many it preserved", () => {
  const root = cleanupFixture();
  try {
    const out = execFileSync("bash", [CLEANUP, "--results-root", root, "--policy", "keep-canonical", "--json"], { encoding: "utf8" });
    const plan = JSON.parse(out);
    assert.ok(plan.canonicalFilesPreserved >= 2);
    assert.ok(plan.filesSelected.every((f) => f.class !== "canonical"));
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

// =============================================================================
// Batch-level storage gates (run-all-benchmarks.sh)
// =============================================================================

function makeBatchEnv() {
  const base = tmp("plab-storage-batch-");
  const content = join(base, "content");
  const confs = join(base, "confs");
  const bin = join(base, "bin");
  mkdirSync(content, { recursive: true });
  mkdirSync(confs, { recursive: true });
  mkdirSync(bin, { recursive: true });

  const lab = "lab-a";
  mkdirSync(join(content, lab), { recursive: true });
  writeFileSync(join(content, lab, "lab.json"), JSON.stringify({ id: lab, benchmark: true }));
  const javaDir = join(base, "code", lab, "java");
  mkdirSync(join(javaDir, "src", "test", "java"), { recursive: true });
  writeFileSync(join(javaDir, "src", "test", "java", "T.java"), "class T {}");
  const relJavaDir = relFromRoot(javaDir);
  writeFileSync(join(confs, `${lab}.conf`), `#!/usr/bin/env bash
LAB_BENCHMARK_READY=1
LAB_MIN_CPUS=2
LAB_COOLDOWN_CLASS="short"
LAB_JAVA_DIR="${relJavaDir}"
EV_SELECTOR="x"
lab_variants() { echo "v1"; }
lab_jmh_args() { echo "-p v=$1"; }
lab_threads() { echo 2; }
lab_cpu_count() { echo 2; }
lab_worker_props() { echo ""; }
lab_rust_evidence_cmd() { echo ""; }
`);

  writeFileSync(join(base, "host.yaml"), `schema_version: 1
host_name: test
max_load_per_core_x100: 20
stability_timeout_seconds: 5
stability_consecutive_samples: 1
stability_sample_interval_seconds: 0
cooldown_seconds:
  short: 0
  standard: 0
  long: 0
cpu_sets:
  pair: [0, 1]
labs:
  lab-a: pair
`);

  writeFileSync(join(bin, "stub-runner.sh"), `#!/usr/bin/env bash
lab="$1"; shift
preflight=0; out=""
while [ $# -gt 0 ]; do
  case "$1" in
    --preflight-only) preflight=1; shift ;;
    --out) out="$2"; shift 2 ;;
    *) shift ;;
  esac
done
[ "$preflight" = "1" ] && exit 0
mkdir -p "$out/\${lab}/linux-fake-1"
echo evidence-marker > "$out/\${lab}/linux-fake-1/marker.txt"
tar -czf "$out/\${lab}-linux-fake1-linux-evidence.tar.gz" -C "$out" "\${lab}"
`);
  chmodSync(join(bin, "stub-runner.sh"), 0o755);
  writeFileSync(join(bin, "stub-verify.sh"), "#!/usr/bin/env bash\nexit 0\n");
  chmodSync(join(bin, "stub-verify.sh"), 0o755);
  writeFileSync(join(bin, "git"), `#!/bin/sh
case "$*" in
  *status*--porcelain*) exit 0 ;;
  *rev-parse*) echo "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"; exit 0 ;;
  *submodule*status*) exit 0 ;;
  *log*) echo '{}'; exit 0 ;;
  *) exit 0 ;;
esac
`);
  chmodSync(join(bin, "git"), 0o755);
  return { base, content, confs, bin, host: join(base, "host.yaml"), batches: join(base, "batches") };
}

function runBatch(env, args, extraEnv = {}) {
  try {
    const stdout = execFileSync("bash", [BATCH, ...args], {
      env: {
        ...process.env,
        PATH: `${env.bin}:/usr/bin:/bin`,
        PLAB_RUNNER_OVERRIDE: join(env.bin, "stub-runner.sh"),
        PLAB_VERIFY_OVERRIDE: join(env.bin, "stub-verify.sh"),
        PLAB_CONTENT_ROOT: env.content,
        PLAB_CONF_DIR: env.confs,
        PLAB_BATCH_ROOT: env.batches,
        ...extraEnv,
      },
      encoding: "utf8", stdio: ["ignore", "pipe", "pipe"],
    });
    return { status: 0, stdout, stderr: "" };
  } catch (err) {
    return { status: err.status ?? 1, stdout: err.stdout?.toString() ?? "", stderr: err.stderr?.toString() ?? "" };
  }
}

test("batch storage preflight: aborts before any lab when available space is below the host config's minimum", () => {
  const env = makeBatchEnv();
  try {
    const r = runBatch(env, ["--profile", "smoke", "--repetitions", "1", "--diagnostic", "--host-config", env.host], {
      PLAB_FAKE_FS_TOTAL_BYTES: "1000000000000", PLAB_FAKE_FS_AVAIL_BYTES: "1000",
    });
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /failed-storage-preflight/);
    assert.equal(existsSync(env.batches), false, "no batch directory should be created — nothing started");
  } finally {
    rmSync(env.base, { recursive: true, force: true });
  }
});

test("batch storage: a per-lab check aborts the whole batch with failed-storage-budget when available space is below the abort threshold", () => {
  const env = makeBatchEnv();
  // Host config's default min_available_before_batch (80 GiB) would also
  // reject this fake value, so give it a tiny override too — this test's
  // subject is specifically the per-lab abort-threshold check that runs
  // once inside the sequential loop, not the earlier whole-batch gate;
  // both share the same fs_available_bytes mechanism.
  writeFileSync(env.host, readFileSync(env.host, "utf8").replace("max_load_per_core_x100: 20", "max_load_per_core_x100: 20\nstorage:\n  min_available_before_batch_gib: 1\n  abort_threshold_during_batch_gib: 1"));
  try {
    const r = runBatch(env, ["--profile", "smoke", "--repetitions", "1", "--diagnostic", "--host-config", env.host], {
      PLAB_FAKE_FS_TOTAL_BYTES: "1000000000000", PLAB_FAKE_FS_AVAIL_BYTES: "500",
    });
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /failed-storage-preflight|failed-storage-budget/);
  } finally {
    rmSync(env.base, { recursive: true, force: true });
  }
});

test("batch archive: total batch bytes exceeding the configured budget aborts before archive creation (failed-artifact-size-limit), evidence stays on disk", () => {
  const env = makeBatchEnv();
  try {
    const r = runBatch(env, ["--profile", "smoke", "--repetitions", "1", "--diagnostic", "--host-config", env.host], {
      PLAB_MAX_BATCH_BYTES: "10",
    });
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /failed-artifact-size-limit/);
    const batchDirs = readdirSync(env.batches).filter((d) => d.startsWith("batch-"));
    assert.equal(batchDirs.length, 1);
    assert.ok(existsSync(join(env.batches, batchDirs[0], "run-1", "lab-a")), "evidence remains on disk even though the archive was refused");
    const archives = readdirSync(join(env.batches, batchDirs[0])).filter((f) => f.startsWith("performance-lab-"));
    assert.equal(archives.length, 0, "no archive should have been built");
  } finally {
    rmSync(env.base, { recursive: true, force: true });
  }
});

test("batch: retain-raw-profiler-data is forwarded to every per-lab runner invocation", () => {
  const env = makeBatchEnv();
  // Replace the stub runner with one that records its own argv so the
  // test can assert the flag was actually passed through.
  writeFileSync(join(env.bin, "stub-runner.sh"), `#!/usr/bin/env bash
echo "ARGS: $*" >> "${env.base}/invoke.log"
lab="$1"; shift
preflight=0; out=""
while [ $# -gt 0 ]; do
  case "$1" in
    --preflight-only) preflight=1; shift ;;
    --out) out="$2"; shift 2 ;;
    *) shift ;;
  esac
done
[ "$preflight" = "1" ] && exit 0
mkdir -p "$out/\${lab}/linux-fake-1"
echo evidence-marker > "$out/\${lab}/linux-fake-1/marker.txt"
tar -czf "$out/\${lab}-linux-fake1-linux-evidence.tar.gz" -C "$out" "\${lab}"
`);
  chmodSync(join(env.bin, "stub-runner.sh"), 0o755);
  try {
    const r = runBatch(env, ["--profile", "smoke", "--repetitions", "1", "--diagnostic", "--host-config", env.host, "--retain-raw-profiler-data"]);
    assert.equal(r.status, 0, r.stderr);
    const log = readFileSync(join(env.base, "invoke.log"), "utf8");
    assert.match(log, /--retain-raw-profiler-data/);
  } finally {
    rmSync(env.base, { recursive: true, force: true });
  }
});

// =============================================================================
// --storage-target: decoupling "where do I write" (--out) from "what
// filesystem must have capacity" (storage-target/results root). Regression
// tests for the batch-storage-target-bug incident (2026-07): the batch's
// live per-lab preflight passed a throwaway mktemp -d under /tmp as --out,
// and the runner's capacity check evaluated THAT ephemeral directory
// instead of the real results root, blocking every lab even though the
// real target had ample space. Everything here uses PLAB_FAKE_FS_MAP
// (per-path fake filesystem stats) so two DIFFERENT paths can be given two
// DIFFERENT simulated capacities without touching a real disk.
// =============================================================================

function fakeFsMap(entries) {
  return JSON.stringify(entries);
}

function runPreflight(lab, { out, storageTarget, fakeMap, extraArgs = [], extraEnv = {} }) {
  const stubs = preflightStubDir();
  try {
    const args = [RUNNER, lab, "--profile", "publication-core", "--cpus", labCpus(lab), "--preflight-only", "--out", out];
    if (storageTarget) args.push("--storage-target", storageTarget);
    args.push(...extraArgs);
    const stdout = execFileSync("bash", args, {
      env: { ...process.env, PATH: `${stubs}:/usr/bin:/bin`, ...(fakeMap ? { PLAB_FAKE_FS_MAP: fakeMap } : {}), ...extraEnv },
      encoding: "utf8", cwd: ROOT, stdio: ["ignore", "pipe", "pipe"],
    });
    return { status: 0, stdout, stderr: "" };
  } catch (err) {
    return { status: err.status ?? 1, stdout: err.stdout?.toString() ?? "", stderr: err.stderr?.toString() ?? "" };
  } finally {
    rmSync(stubs, { recursive: true, force: true });
  }
}

test("storage-target #1: a 25 GiB /tmp-like --out never blocks a run whose --storage-target has 100 GiB", () => {
  const scratch = tmp("plab-st-scratch-");
  const results = tmp("plab-st-results-");
  try {
    const map = fakeFsMap({
      [scratch]: { totalBytes: 26843545600, usedBytes: 5000000000, availableBytes: 21843545600, statDev: 111 },
      [results]: { totalBytes: 107374182400, usedBytes: 5000000000, availableBytes: 102374182400, statDev: 222 },
      [ROOT]: { totalBytes: 500000000000, usedBytes: 5000000000, availableBytes: 495000000000, statDev: 333 },
    });
    const r = runPreflight("mesi", { out: scratch, storageTarget: results, fakeMap: map });
    assert.doesNotMatch(r.stderr, /failed-storage-preflight/, r.stderr);
    const runDir = findRunDir(scratch, "mesi");
    const fsPreflight = JSON.parse(readFileSync(join(runDir, "filesystem-preflight.json"), "utf8"));
    assert.equal(fsPreflight.temporaryOutputDirectory, scratch);
    assert.equal(fsPreflight.storageBudgetTarget, results);
    assert.equal(fsPreflight.resultsFilesystem.withinBudget, true);
    assert.equal(fsPreflight.resultsFilesystem.availableBytes, 102374182400);
  } finally {
    rmAll(scratch, results);
  }
});

test("storage-target #2: a 100 GiB /tmp-like --out never rescues a run whose --storage-target has only 25 GiB", () => {
  const scratch = tmp("plab-st-scratch-");
  const results = tmp("plab-st-results-");
  try {
    const map = fakeFsMap({
      [scratch]: { totalBytes: 500000000000, usedBytes: 5000000000, availableBytes: 495000000000, statDev: 111 },
      [results]: { totalBytes: 26843545600, usedBytes: 20000000000, availableBytes: 6843545600, statDev: 222 },
      [ROOT]: { totalBytes: 500000000000, usedBytes: 5000000000, availableBytes: 495000000000, statDev: 333 },
    });
    const r = runPreflight("mesi", { out: scratch, storageTarget: results, fakeMap: map });
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /failed-storage-preflight/);
    assert.match(r.stderr, new RegExp(results.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")), "the message must name the real target, not the scratch directory");
    const runDir = findRunDir(scratch, "mesi");
    const status = JSON.parse(readFileSync(join(runDir, "run-status.json"), "utf8"));
    assert.equal(status.runStatus, "rejected");
    assert.equal(status.storageDiagnostics.checkedPath, results);
    assert.equal(status.storageDiagnostics.availableBytes, 6843545600);
  } finally {
    rmAll(scratch, results);
  }
});

test("storage-target: defaults to --out when not given (standalone invocation, unchanged prior behavior)", () => {
  const out = tmp("plab-st-default-");
  try {
    const map = fakeFsMap({
      [out]: { totalBytes: 26843545600, usedBytes: 20000000000, availableBytes: 6843545600, statDev: 111 },
      [ROOT]: { totalBytes: 500000000000, usedBytes: 5000000000, availableBytes: 495000000000, statDev: 333 },
    });
    const r = runPreflight("mesi", { out, fakeMap: map });
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /failed-storage-preflight/);
    const runDir = findRunDir(out, "mesi");
    const fsPreflight = JSON.parse(readFileSync(join(runDir, "filesystem-preflight.json"), "utf8"));
    assert.equal(fsPreflight.storageBudgetTarget, out, "no --storage-target given: falls back to --out, exactly as before this fix");
  } finally {
    rmAll(out);
  }
});

test("storage-target #6: smoke profile with a tiny --out still resolves capacity against --storage-target, not the tiny scratch dir", () => {
  const scratch = tmp("plab-st-scratch-");
  const results = tmp("plab-st-results-");
  try {
    const map = fakeFsMap({
      [scratch]: { totalBytes: 2147483648, usedBytes: 1000000000, availableBytes: 1147483648, statDev: 111 }, // ~2 GiB tiny /tmp
      [results]: { totalBytes: 107374182400, usedBytes: 5000000000, availableBytes: 102374182400, statDev: 222 },
      [ROOT]: { totalBytes: 500000000000, usedBytes: 5000000000, availableBytes: 495000000000, statDev: 333 },
    });
    const r = runPreflight("mesi", { out: scratch, storageTarget: results, fakeMap: map, extraArgs: ["--profile", "smoke"] });
    assert.doesNotMatch(r.stderr, /failed-storage-preflight/, r.stderr);
  } finally {
    rmAll(scratch, results);
  }
});

test("storage-target #7: an empty/missing storage target is rejected explicitly, never silently substituted", () => {
  const r = libCall(`validate_storage_target ''`);
  assert.notEqual(r.status, 0);
  assert.match(r.stderr, /empty path/);
});

test("storage-target #8: an unwritable storage target is rejected explicitly", () => {
  const parent = tmp("plab-st-unwritable-");
  const target = join(parent, "readonly-child");
  mkdirSync(target);
  try {
    chmodSync(target, 0o555);
    const r = libCall(`validate_storage_target '${target}'`);
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /not writable/);
  } finally {
    chmodSync(target, 0o755);
    rmAll(parent);
  }
});

test("storage-target #9/#11: repository and results on the same filesystem produce sameFilesystemAsRepo: true (never null)", () => {
  const out = tmp("plab-st-same-");
  try {
    const map = fakeFsMap({
      [out]: { totalBytes: 107374182400, usedBytes: 5000000000, availableBytes: 102374182400, statDev: 999 },
      [ROOT]: { totalBytes: 107374182400, usedBytes: 5000000000, availableBytes: 102374182400, statDev: 999 },
    });
    const r = runPreflight("mesi", { out, fakeMap: map });
    assert.doesNotMatch(r.stderr, /failed-storage-preflight/, r.stderr);
    const runDir = findRunDir(out, "mesi");
    const fsPreflight = JSON.parse(readFileSync(join(runDir, "filesystem-preflight.json"), "utf8"));
    assert.strictEqual(fsPreflight.sameFilesystemAsRepo, true);
  } finally {
    rmAll(out);
  }
});

test("storage-target #10/#11: different filesystems produce sameFilesystemAsRepo: false (never null)", () => {
  const out = tmp("plab-st-diff-");
  try {
    const map = fakeFsMap({
      [out]: { totalBytes: 107374182400, usedBytes: 5000000000, availableBytes: 102374182400, statDev: 111 },
      [ROOT]: { totalBytes: 500000000000, usedBytes: 5000000000, availableBytes: 495000000000, statDev: 222 },
    });
    const r = runPreflight("mesi", { out, fakeMap: map });
    assert.doesNotMatch(r.stderr, /failed-storage-preflight/, r.stderr);
    const runDir = findRunDir(out, "mesi");
    const fsPreflight = JSON.parse(readFileSync(join(runDir, "filesystem-preflight.json"), "utf8"));
    assert.strictEqual(fsPreflight.sameFilesystemAsRepo, false);
  } finally {
    rmAll(out);
  }
});

test("storage-target #12: rejection carries structured, untruncated storage diagnostics (never a clipped human string)", () => {
  const scratch = tmp("plab-st-scratch-");
  const results = tmp("plab-st-results-");
  try {
    const map = fakeFsMap({
      [scratch]: { totalBytes: 500000000000, usedBytes: 5000000000, availableBytes: 495000000000, statDev: 111 },
      [results]: { totalBytes: 26843545600, usedBytes: 20000000000, availableBytes: 6843545600, statDev: 222 },
      [ROOT]: { totalBytes: 500000000000, usedBytes: 5000000000, availableBytes: 495000000000, statDev: 333 },
    });
    const r = runPreflight("mesi", { out: scratch, storageTarget: results, fakeMap: map });
    assert.notEqual(r.status, 0);
    const runDir = findRunDir(scratch, "mesi");
    const status = JSON.parse(readFileSync(join(runDir, "run-status.json"), "utf8"));
    const diag = status.storageDiagnostics;
    assert.equal(diag.reasonCode, "failed-storage-preflight");
    assert.equal(diag.checkedPath, results);
    assert.equal(typeof diag.mountPoint, "string");
    assert.equal(diag.availableBytes, 6843545600);
    assert.equal(diag.requiredBytes, 85899345920);
    assert.ok(diag.message.length > 20, "message must be the full, untruncated explanation, not a clipped fragment");
  } finally {
    rmAll(scratch, results);
  }
});

// --- batch-level: storage-target wiring through run-all-benchmarks.sh -------

test("batch #3: live per-lab preflight writes to a throwaway --out but always passes --storage-target pointing at the real results root", () => {
  const env = makeBatchEnv();
  writeFileSync(join(env.bin, "stub-runner.sh"), `#!/usr/bin/env bash
echo "ARGS: $*" >> "${env.base}/invoke.log"
lab="$1"; shift
preflight=0; out=""
while [ $# -gt 0 ]; do
  case "$1" in
    --preflight-only) preflight=1; shift ;;
    --out) out="$2"; shift 2 ;;
    *) shift ;;
  esac
done
[ "$preflight" = "1" ] && exit 0
mkdir -p "$out/\${lab}/linux-fake-1"
echo evidence-marker > "$out/\${lab}/linux-fake-1/marker.txt"
tar -czf "$out/\${lab}-linux-fake1-linux-evidence.tar.gz" -C "$out" "\${lab}"
`);
  chmodSync(join(env.bin, "stub-runner.sh"), 0o755);
  try {
    const r = runBatch(env, ["--profile", "smoke", "--repetitions", "1", "--diagnostic", "--host-config", env.host]);
    assert.equal(r.status, 0, r.stderr);
    const log = readFileSync(join(env.base, "invoke.log"), "utf8");
    const preflightLine = log.split("\n").find((l) => l.includes("--preflight-only"));
    assert.ok(preflightLine, "expected a --preflight-only invocation to be logged");
    assert.match(preflightLine, /--storage-target/, "the live per-lab preflight invocation must pass --storage-target");
    // the --storage-target value must NOT be the ephemeral --out used for
    // this same invocation — they are deliberately different directories
    const outMatch = preflightLine.match(/--out (\S+)/);
    const targetMatch = preflightLine.match(/--storage-target (\S+)/);
    assert.ok(outMatch && targetMatch, preflightLine);
    assert.notEqual(outMatch[1], targetMatch[1], "--out (ephemeral) and --storage-target (real results root) must differ");
  } finally {
    rmSync(env.base, { recursive: true, force: true });
  }
});

test("batch #4: the whole-batch storage preflight runs against the results root BEFORE any per-lab live preflight", () => {
  const env = makeBatchEnv();
  try {
    const r = runBatch(env, ["--profile", "smoke", "--repetitions", "1", "--diagnostic", "--host-config", env.host], {
      PLAB_FAKE_FS_TOTAL_BYTES: "1000000000000", PLAB_FAKE_FS_AVAIL_BYTES: "1000",
    });
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /failed-storage-preflight/);
    assert.doesNotMatch(r.stdout, /Live preflight/, "the live-preflight phase must never start once the whole-batch check has failed");
  } finally {
    rmSync(env.base, { recursive: true, force: true });
  }
});

test("batch #5: per-variant storage checks (inside a real measurement, not preflight) use the real results filesystem", () => {
  // Exercised at the unit level: check_storage_or_abort inside
  // run-linux-evidence.sh reads $STORAGE_TARGET, and STORAGE_TARGET
  // defaults to $OUT_ROOT for a real (non-preflight) measurement — this is
  // already covered end-to-end by the pre-existing "run-total budget"
  // test (scripts/test-evidence-storage.js) using the synthetic-lab
  // harness; this test adds the explicit-storage-target variant.
  const lab = makeSyntheticLab("stat", ["v1", "v2", "v3"], null);
  const out = tmp("plab-full-out-");
  const storageTarget = tmp("plab-full-storage-");
  try {
    const r = runFull(lab, ["--storage-target", storageTarget], { PLAB_MAX_BATCH_BYTES: "50" }, out);
    assert.equal(r.status, 4, r.stderr);
    assert.match(r.stderr, /STORAGE ABORT/);
    const runDir = findRunDir(out, lab.labId);
    const status = JSON.parse(readFileSync(join(runDir, "run-status.json"), "utf8"));
    assert.match(status.runStatus, /failed-storage-budget/);
    assert.equal(status.storageDiagnostics.checkedPath, runDir, "the total-run-bytes-budget check reports the run directory itself, which lives under the explicit storage target");
  } finally {
    rmAll(lab.labsDir, lab.javaDir, out, storageTarget);
  }
});

test("batch #13: TMPDIR may influence where the live-preflight scratch directory is created, but never affects storage-target selection", () => {
  // run-all-benchmarks.sh creates its ephemeral live-preflight directory
  // with a bare `mktemp -d` — GNU mktemp (the real Linux measurement host)
  // honors $TMPDIR there by default, which is all "correctness must not
  // DEPEND on TMPDIR, but MAY use it" requires; this dev/test machine's
  // BSD mktemp does not honor $TMPDIR for a bare `mktemp -d` at all
  // (verified: `env TMPDIR=/custom mktemp -d` still prints a path under
  // the real system temp dir), so this test does not assert on --out's
  // location — only on the invariant that is actually this fix's
  // contract: --storage-target is always the real results root,
  // completely unaffected by whatever TMPDIR is set to.
  const env = makeBatchEnv();
  const customTmp = tmp("plab-st-customtmp-");
  writeFileSync(join(env.bin, "stub-runner.sh"), `#!/usr/bin/env bash
echo "ARGS: $*" >> "${env.base}/invoke.log"
lab="$1"; shift
preflight=0; out=""
while [ $# -gt 0 ]; do
  case "$1" in
    --preflight-only) preflight=1; shift ;;
    --out) out="$2"; shift 2 ;;
    *) shift ;;
  esac
done
[ "$preflight" = "1" ] && exit 0
mkdir -p "$out/\${lab}/linux-fake-1"
echo evidence-marker > "$out/\${lab}/linux-fake-1/marker.txt"
tar -czf "$out/\${lab}-linux-fake1-linux-evidence.tar.gz" -C "$out" "\${lab}"
`);
  chmodSync(join(env.bin, "stub-runner.sh"), 0o755);
  try {
    const r = runBatch(env, ["--profile", "smoke", "--repetitions", "1", "--diagnostic", "--host-config", env.host], { TMPDIR: customTmp });
    assert.equal(r.status, 0, r.stderr);
    const log = readFileSync(join(env.base, "invoke.log"), "utf8");
    const preflightLine = log.split("\n").find((l) => l.includes("--preflight-only"));
    const targetMatch = preflightLine.match(/--storage-target (\S+)/);
    assert.ok(targetMatch, preflightLine);
    assert.ok(!targetMatch[1].startsWith(customTmp), "the storage-target must be the real results root, never redirected by TMPDIR");
  } finally {
    rmSync(env.base, { recursive: true, force: true });
    rmSync(customTmp, { recursive: true, force: true });
  }
});

test("batch #14: no per-lab measurement ever starts after a genuine results-filesystem storage failure", () => {
  const env = makeBatchEnv();
  writeFileSync(join(env.bin, "stub-runner.sh"), `#!/usr/bin/env bash
echo "INVOKE: $*" >> "${env.base}/invoke.log"
lab="$1"; shift
preflight=0; out=""
while [ $# -gt 0 ]; do
  case "$1" in
    --preflight-only) preflight=1; shift ;;
    --out) out="$2"; shift 2 ;;
    *) shift ;;
  esac
done
[ "$preflight" = "1" ] && exit 0
echo "REAL MEASUREMENT STARTED" >> "${env.base}/invoke.log"
mkdir -p "$out/\${lab}/linux-fake-1"
echo evidence-marker > "$out/\${lab}/linux-fake-1/marker.txt"
tar -czf "$out/\${lab}-linux-fake1-linux-evidence.tar.gz" -C "$out" "\${lab}"
`);
  chmodSync(join(env.bin, "stub-runner.sh"), 0o755);
  try {
    const r = runBatch(env, ["--profile", "smoke", "--repetitions", "1", "--diagnostic", "--host-config", env.host], {
      PLAB_FAKE_FS_TOTAL_BYTES: "1000000000000", PLAB_FAKE_FS_AVAIL_BYTES: "1000",
    });
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /failed-storage-preflight/);
    assert.equal(existsSync(join(env.base, "invoke.log")), false, "the per-lab runner must never be invoked at all — neither preflight nor real measurement");
  } finally {
    rmSync(env.base, { recursive: true, force: true });
  }
});

// =============================================================================
// Evidence-manifest component model (2026-07-27 manifest-generation defect —
// batch-20260727T121031Z, 18 verification-failed runs): a variant is a
// parameter combination; JMH, an aux harness, perf stat, perf c2c, the Rust
// harness and worker-placement evidence are COMPONENTS of that variant, each
// one of exactly six states, never a static path placeholder for a
// component that was never scheduled or does not apply.
// =============================================================================

test("component model: a stat-only variant declares perfC2c not-scheduled with no artifact paths", () => {
  const lab = makeSyntheticLab("stat", ["v1"], null);
  const out = tmp("plab-full-out-");
  try {
    const r = runFull(lab, [], {}, out);
    assert.equal(r.status, 0, r.stderr);
    const runDir = findRunDir(out, lab.labId);
    const manifest = JSON.parse(readFileSync(join(runDir, "evidence-manifest.json"), "utf8"));
    const c2c = manifest.variants.v1.components.perfC2c;
    assert.equal(c2c.status, "not-scheduled");
    assert.equal("report" in c2c, false, "not-scheduled must declare zero required artifact paths");
    assert.equal("retentionMetadata" in c2c, false);
    assert.equal(existsSync(join(runDir, "v1", "perf-c2c-report.txt")), false);
  } finally {
    rmAll(lab.labsDir, lab.javaDir, out);
  }
});

test("component model: a c2c-policy variant is retained-summary-only and verify-evidence.sh accepts it without the raw perf-c2c.data", () => {
  const lab = makeSyntheticLab("c2c", ["v1"], null);
  const reportFile = join(lab.javaDir, "report.txt");
  writeFileSync(reportFile, "Total records processed: 10\nShared Data Cache Line Table\nHITM local 1 remote 0\n");
  const out = tmp("plab-full-out-");
  try {
    const r = runFull(lab, [], {}, out, reportFile);
    assert.equal(r.status, 0, r.stderr);
    const runDir = findRunDir(out, lab.labId);
    const manifest = JSON.parse(readFileSync(join(runDir, "evidence-manifest.json"), "utf8"));
    const c2c = manifest.variants.v1.components.perfC2c;
    assert.equal(c2c.status, "retained-summary-only");
    assert.equal(c2c.rawProfiler.retained, false);
    assert.equal(c2c.rawProfiler.sha256BeforeDeletion.length, 64);
    assert.equal(existsSync(join(runDir, "v1", "perf-c2c.data")), false);
    const archive = execFileSync("bash", ["-c", `ls '${out}'/${lab.labId}-*-linux-evidence.tar.* 2>/dev/null | head -1`], { encoding: "utf8" }).trim();
    const verify = (() => {
      try { return { status: 0, out: execFileSync("bash", [VERIFY_EVIDENCE, archive], { encoding: "utf8" }) }; }
      catch (err) { return { status: err.status ?? 1, out: (err.stdout ?? "") + (err.stderr ?? "") }; }
    })();
    assert.equal(verify.status, 0, verify.out);
  } finally {
    rmAll(lab.labsDir, lab.javaDir, out);
  }
});

test("component model: a lab with no Rust harness declares rustHarness not-applicable, never rustEvidence", () => {
  const lab = makeSyntheticLab("stat", ["v1"], null);
  const out = tmp("plab-full-out-");
  try {
    const r = runFull(lab, [], {}, out);
    assert.equal(r.status, 0, r.stderr);
    const runDir = findRunDir(out, lab.labId);
    const manifest = JSON.parse(readFileSync(join(runDir, "evidence-manifest.json"), "utf8"));
    const rust = manifest.variants.v1.components.rustHarness;
    assert.equal(rust.status, "not-applicable");
    assert.equal("artifacts" in rust, false);
    assert.equal(existsSync(join(runDir, "v1", "rust-evidence.json")), false);
  } finally {
    rmAll(lab.labsDir, lab.javaDir, out);
  }
});

// A lab that DOES have a Rust harness in general, but whose evidence
// command is only defined for one of its two variants (mirrors SPSC's
// harness-* variants, which deliberately never get a Rust run).
function makeSyntheticLabWithPartialRust() {
  const labsDir = tmp("plab-synth-labs-");
  const javaDir = tmp("plab-synth-java-");
  const rustDir = tmp("plab-synth-rust-");
  const labId = "storage-synthetic-rust-lab";
  const conf = `#!/usr/bin/env bash
LAB_BENCHMARK_READY=1
LAB_CPUS_EXACT=2
LAB_MIN_CPUS=2
LAB_JAVA_DIR_ABS="${javaDir}"
LAB_JAVA_DIR="\${LAB_JAVA_DIR_ABS#\${REPO_ROOT}/}"
LAB_RUST_DIR_ABS="${rustDir}"
LAB_RUST_DIR="\${LAB_RUST_DIR_ABS#\${REPO_ROOT}/}"
EV_SELECTOR="fake.Selector"
LAB_C2C_REQUIRED=0
LAB_PROFILER_POLICY="stat"
lab_variants() { echo "supported unsupported"; }
lab_jmh_args() { echo ""; }
lab_threads() { echo 1; }
lab_cpu_count() { echo 1; }
lab_worker_props() { echo ""; }
lab_rust_evidence_cmd() { case "\$1" in supported) echo "echo fake-rust-run" ;; *) echo "" ;; esac; }
`;
  writeFileSync(join(labsDir, `${labId}.conf`), conf);
  return { labsDir, javaDir, rustDir, labId };
}

test("component model: a variant unsupported by the lab's own Rust harness is not-applicable (never not-scheduled)", () => {
  const lab = makeSyntheticLabWithPartialRust();
  const out = tmp("plab-full-out-");
  try {
    const rel = relative(ROOT, lab.rustDir);
    let conf = readFileSync(join(lab.labsDir, `${lab.labId}.conf`), "utf8");
    conf = conf.replace(/LAB_RUST_DIR_ABS=.*\n/, "").replace(/LAB_RUST_DIR=.*\n/, `LAB_RUST_DIR="${rel}"\n`);
    writeFileSync(join(lab.labsDir, `${lab.labId}.conf`), conf);
    const r = runFull(lab, [], {}, out);
    assert.equal(r.status, 0, r.stderr);
    const runDir = findRunDir(out, lab.labId);
    const manifest = JSON.parse(readFileSync(join(runDir, "evidence-manifest.json"), "utf8"));
    assert.equal(manifest.variants.unsupported.components.rustHarness.status, "not-applicable");
    assert.equal("artifacts" in manifest.variants.unsupported.components.rustHarness, false);
    // the supported variant still gets a real completed Rust component —
    // "no rust for this lab" and "no rust for this variant" are genuinely
    // distinct, never collapsed into the same reason.
    assert.equal(manifest.variants.supported.components.rustHarness.status, "completed");
  } finally {
    rmAll(lab.labsDir, lab.javaDir, lab.rustDir, out);
  }
});

test("component model: component names and variant names remain distinct — six fixed component keys regardless of variant naming", () => {
  const lab = makeSyntheticLab("stat", ["harness-v1", "v2"], null);
  const out = tmp("plab-full-out-");
  try {
    const r = runFull(lab, [], {}, out);
    assert.equal(r.status, 0, r.stderr);
    const runDir = findRunDir(out, lab.labId);
    const manifest = JSON.parse(readFileSync(join(runDir, "evidence-manifest.json"), "utf8"));
    assert.deepEqual(Object.keys(manifest.variants).sort(), ["harness-v1", "v2"].sort());
    for (const v of Object.keys(manifest.variants)) {
      assert.deepEqual(
        Object.keys(manifest.variants[v].components).sort(),
        ["auxHarness", "jmh", "perfC2c", "perfStat", "rustHarness", "workerPlacement"].sort(),
        `variant ${v} must use the fixed component-name set, never a synthetic "harness-<variant>" entry`,
      );
    }
    // No synthetic top-level "harness-<variant>" manifest entries — variant
    // names are exactly what lab_variants() returned, nothing derived.
    assert.equal(Object.keys(manifest.variants).some((v) => v.startsWith("harness-harness-")), false);
  } finally {
    rmAll(lab.labsDir, lab.javaDir, out);
  }
});

test("component model: every artifact path a finalized manifest declares for a completed/retained component actually exists on disk", () => {
  const lab = makeSyntheticLab("c2c", ["v1"], null);
  const reportFile = join(lab.javaDir, "report.txt");
  writeFileSync(reportFile, "Total records processed: 10\n");
  const out = tmp("plab-full-out-");
  try {
    const r = runFull(lab, [], {}, out, reportFile);
    assert.equal(r.status, 0, r.stderr);
    const runDir = findRunDir(out, lab.labId);
    const manifest = JSON.parse(readFileSync(join(runDir, "evidence-manifest.json"), "utf8"));
    const missing = [];
    const checkPath = (p) => { if (typeof p === "string" && !existsSync(join(runDir, p))) missing.push(p); };
    for (const variant of Object.values(manifest.variants)) {
      for (const comp of Object.values(variant.components)) {
        if (comp.status !== "completed" && comp.status !== "retained-summary-only") continue;
        if (comp.report) checkPath(comp.report);
        if (comp.retentionMetadata) checkPath(comp.retentionMetadata);
        for (const v of Object.values(comp.artifacts ?? {})) {
          if (Array.isArray(v)) v.forEach(checkPath); else checkPath(v);
        }
        if (comp.perWorkerArtifact) checkPath(comp.perWorkerArtifact);
      }
    }
    assert.deepEqual(missing, [], "a finalized manifest must never point at a nonexistent artifact");
  } finally {
    rmAll(lab.labsDir, lab.javaDir, out);
  }
});

// --- verify-evidence.sh: component-status-aware rejection (fixture archives,
// no live measurement) -------------------------------------------------------

function makeManifestFixture(variantsObj) {
  const base = tmp("plab-verify-fixture-");
  const dir = join(base, "linux-fixture");
  mkdirSync(dir, { recursive: true });
  for (const f of ["environment.json", "topology.txt", "capabilities.json", "toolchain.json", "benchmark-profile.json", "correctness.json"]) {
    writeFileSync(join(dir, f), "{}");
  }
  writeFileSync(join(dir, "evidence-manifest.json"), JSON.stringify({
    labId: "fixture-lab", runId: "linux-fixture", sourceCommit: "abc123def456", dirtyTree: false, publicationEligible: true, scenario: "same-socket",
    environment: "environment.json", topology: "topology.txt", capabilities: "capabilities.json",
    toolchain: "toolchain.json", benchmarkProfile: "benchmark-profile.json", correctness: "correctness.json",
    variants: variantsObj,
  }));
  execFileSync("bash", ["-c", `source '${EVIDENCE_LIB}'; write_sha256sums '${dir}'`]);
  const archive = join(base, "fixture-lab-linux-fixture-linux-evidence.tar.gz");
  execSync(`tar -czf '${archive}' -C '${base}' 'linux-fixture'`);
  return { dir, base, archive };
}

function rehashAndRetar(dir, base, archive) {
  execFileSync("bash", ["-c", `source '${EVIDENCE_LIB}'; write_sha256sums '${dir}'`]);
  execSync(`rm -f '${archive}' && tar -czf '${archive}' -C '${base}' 'linux-fixture'`);
}

function runVerifyEvidence(archive) {
  try {
    const stdout = execFileSync("bash", [VERIFY_EVIDENCE, archive], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
    return { status: 0, stdout, stderr: "" };
  } catch (err) {
    return { status: err.status ?? 1, stdout: err.stdout?.toString() ?? "", stderr: err.stderr?.toString() ?? "" };
  }
}

test("verify-evidence: retained-summary-only fails when the bounded report is missing", () => {
  const { dir, base, archive } = makeManifestFixture({
    v1: { kind: "jmh", components: {
      jmh: { status: "not-applicable", reason: "x" }, auxHarness: { status: "not-applicable", reason: "x" },
      perfStat: { status: "not-scheduled", reason: "x" },
      perfC2c: { status: "retained-summary-only", report: "v1/perf-c2c-report.txt", retentionMetadata: "v1/raw-profiler-retention.json", rawProfiler: { retained: false, sha256BeforeDeletion: "a".repeat(64), originalBytes: 10 } },
      rustHarness: { status: "not-applicable", reason: "x" }, workerPlacement: { status: "not-applicable", reason: "x" },
    } },
  });
  try {
    mkdirSync(join(dir, "v1"), { recursive: true });
    writeFileSync(join(dir, "v1", "raw-profiler-retention.json"), "{}");
    // perf-c2c-report.txt deliberately NOT created
    rehashAndRetar(dir, base, archive);
    const r = runVerifyEvidence(archive);
    assert.notEqual(r.status, 0);
    assert.match(r.stdout + r.stderr, /missing artifact/);
  } finally {
    rmAll(base);
  }
});

test("verify-evidence: retained-summary-only fails when the retention metadata is missing", () => {
  const { dir, base, archive } = makeManifestFixture({
    v1: { kind: "jmh", components: {
      jmh: { status: "not-applicable", reason: "x" }, auxHarness: { status: "not-applicable", reason: "x" },
      perfStat: { status: "not-scheduled", reason: "x" },
      perfC2c: { status: "retained-summary-only", report: "v1/perf-c2c-report.txt", retentionMetadata: "v1/raw-profiler-retention.json", rawProfiler: { retained: false, sha256BeforeDeletion: "a".repeat(64), originalBytes: 10 } },
      rustHarness: { status: "not-applicable", reason: "x" }, workerPlacement: { status: "not-applicable", reason: "x" },
    } },
  });
  try {
    mkdirSync(join(dir, "v1"), { recursive: true });
    writeFileSync(join(dir, "v1", "perf-c2c-report.txt"), "# summary\n");
    // raw-profiler-retention.json deliberately NOT created
    rehashAndRetar(dir, base, archive);
    const r = runVerifyEvidence(archive);
    assert.notEqual(r.status, 0);
    assert.match(r.stdout + r.stderr, /missing artifact/);
  } finally {
    rmAll(base);
  }
});

test("verify-evidence: a completed rustHarness component requires its evidence file to exist", () => {
  const { dir, base, archive } = makeManifestFixture({
    v1: { kind: "jmh", components: {
      jmh: { status: "completed", artifacts: { result: "v1/jmh.json" } }, auxHarness: { status: "not-applicable", reason: "x" },
      perfStat: { status: "not-scheduled", reason: "x" }, perfC2c: { status: "not-scheduled", reason: "x" },
      rustHarness: { status: "completed", artifacts: { evidence: "v1/rust-evidence.json", perfStat: "v1/rust-perf-stat.csv" } },
      workerPlacement: { status: "not-applicable", reason: "x" },
    } },
  });
  try {
    mkdirSync(join(dir, "v1"), { recursive: true });
    writeFileSync(join(dir, "v1", "jmh.json"), "{}");
    writeFileSync(join(dir, "v1", "rust-perf-stat.csv"), "x");
    // rust-evidence.json deliberately NOT created
    rehashAndRetar(dir, base, archive);
    const r = runVerifyEvidence(archive);
    assert.notEqual(r.status, 0);
    assert.match(r.stdout + r.stderr, /rust-evidence\.json/);
  } finally {
    rmAll(base);
  }
});

test("verify-evidence: provenance invariant — dirtyTree=true with publicationEligible=true is rejected independently of the generator", () => {
  const { base, archive } = makeManifestFixture({});
  const manifestPath = join(base, "linux-fixture", "evidence-manifest.json");
  const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
  manifest.dirtyTree = true;
  manifest.publicationEligible = true;
  writeFileSync(manifestPath, JSON.stringify(manifest));
  try {
    rehashAndRetar(join(base, "linux-fixture"), base, archive);
    const r = runVerifyEvidence(archive);
    assert.notEqual(r.status, 0);
    assert.match(r.stdout + r.stderr, /provenance invariant violated/);
  } finally {
    rmAll(base);
  }
});
