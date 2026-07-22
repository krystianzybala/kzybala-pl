// Tests for the evidence storage/retention system
// (docs/evidence-storage-retention.md), added after the 2026-07 incident
// where results/ grew to ~131 GB and filled a 295 GB /home filesystem to
// zero headroom. Everything here uses FAKE filesystem statistics
// (PLAB_FAKE_FS_* hooks in lib/storage-lib.sh) and small fixture files —
// nothing in this file creates a large file or fills a real disk to test
// a "disk is full" or "artifact too big" path.
import test from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import {
  chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync, readdirSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join, relative } from "node:path";

const ROOT = join(import.meta.dirname, "..");
const STORAGE_LIB = join(ROOT, "scripts", "performance-lab", "lib", "storage-lib.sh");
const RUNNER = join(ROOT, "scripts", "performance-lab", "run-linux-evidence.sh");
const BATCH = join(ROOT, "scripts", "performance-lab", "run-all-benchmarks.sh");
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
  try {
    const stdout = execFileSync("bash", [RUNNER, labSpec.labId, "--cpus", "0,1", "--skip-load-check", "--out", out, ...args], {
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

test("provenance: environment.json and the manifest record the output root, its filesystem, and whether it shares the repository's filesystem", () => {
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
    assert.equal(typeof env.outputFilesystem.availableBytes, "number");
    assert.equal(typeof env.outputFilesystem.sameFilesystemAsRepo, "boolean");
    assert.equal(env.outputFilesystem.sameFilesystemAsRepo, true, "both are under the OS tmp dir in this test");
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
