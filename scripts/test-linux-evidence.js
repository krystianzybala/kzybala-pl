// Tests for the native-Linux evidence workflow (scripts/performance-lab/).
// Everything here runs without PMU access and without a live perf: topology
// validation, command construction, placement policy, hashing and the
// runner's preflight failure paths are exercised against fixtures and stub
// executables. No test fabricates a successful live `perf c2c` execution —
// command *construction* is tested, execution is not simulated as success.
import test from "node:test";
import assert from "node:assert/strict";
import { execFileSync, execSync } from "node:child_process";
import { createHash } from "node:crypto";
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { IMPORTER_CAPABILITIES } from "./benchmark-platform/results/capability-registry.js";
import { importPerfCounterCsv } from "./benchmark-platform/results/perf-counter-importer.js";
import { deriveMaturity } from "./benchmark-platform/results/evidence-maturity.js";
import { canCompare, environmentClassOf } from "./benchmark-platform/results/comparison-guard.js";
import { renderTable } from "./benchmark-platform/results/render.js";
import { compareToHistory } from "./benchmark-platform/results/regression.js";
import { legacyResultsFor } from "./benchmark-platform/results/legacy-data.js";

const ROOT = join(import.meta.dirname, "..");
const LIB = join(ROOT, "scripts", "performance-lab", "lib", "evidence-lib.sh");
const RUNNER = join(ROOT, "scripts", "performance-lab", "run-linux-evidence.sh");
const FIXTURES = join(ROOT, "scripts", "performance-lab", "__fixtures__");
const LSCPU_FIXTURE = join(FIXTURES, "lscpu-e-smt-two-sockets.txt");

// Runs a function from evidence-lib.sh; returns { status, stdout, stderr }.
function libCall(script, env = {}) {
  try {
    const stdout = execFileSync("bash", ["-c", `source '${LIB}'; ${script}`], {
      env: { ...process.env, ...env },
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    });
    return { status: 0, stdout, stderr: "" };
  } catch (err) {
    return { status: err.status ?? 1, stdout: err.stdout?.toString() ?? "", stderr: err.stderr?.toString() ?? "" };
  }
}

// --- topology validation -----------------------------------------------------

test("topology: accepts two distinct physical cores on one socket/node", () => {
  const r = libCall(`topo_validate_cpus '${LSCPU_FIXTURE}' 0 1 0`);
  assert.equal(r.status, 0, r.stderr);
  assert.match(r.stdout, /coreA=0 .*coreB=1/);
});

test("topology: rejects SMT siblings (same physical core)", () => {
  const r = libCall(`topo_validate_cpus '${LSCPU_FIXTURE}' 0 2 0`);
  assert.notEqual(r.status, 0);
  assert.match(r.stderr, /SMT siblings/);
});

test("topology: rejects cross-socket placement without the explicit override", () => {
  const r = libCall(`topo_validate_cpus '${LSCPU_FIXTURE}' 0 4 0`);
  assert.notEqual(r.status, 0);
  assert.match(r.stderr, /--allow-cross-socket/);
});

test("topology: accepts cross-socket placement with the explicit override, classified as its own scenario", () => {
  const r = libCall(`topo_validate_cpus '${LSCPU_FIXTURE}' 0 4 1`);
  assert.equal(r.status, 0, r.stderr);
  const scenario = libCall(`topo_scenario '${LSCPU_FIXTURE}' 0 4`);
  assert.equal(scenario.stdout.trim(), "cross-socket");
  const same = libCall(`topo_scenario '${LSCPU_FIXTURE}' 0 1`);
  assert.equal(same.stdout.trim(), "same-socket");
});

test("topology: rejects offline, nonexistent and identical CPUs", () => {
  assert.match(libCall(`topo_validate_cpus '${LSCPU_FIXTURE}' 0 8 0`).stderr, /offline/);
  assert.match(libCall(`topo_validate_cpus '${LSCPU_FIXTURE}' 0 99 0`).stderr, /does not exist/);
  assert.match(libCall(`topo_validate_cpus '${LSCPU_FIXTURE}' 3 3 0`).stderr, /two different logical CPUs/);
});

// --- load + migration policy --------------------------------------------------

test("load policy: idle host passes, busy host fails", () => {
  assert.equal(libCall(`check_load '${join(FIXTURES, "loadavg-idle.txt")}' 8 20`).status, 0);
  const busy = libCall(`check_load '${join(FIXTURES, "loadavg-busy.txt")}' 8 20`);
  assert.notEqual(busy.status, 0);
  assert.match(busy.stderr, /material background load/);
});

test("migration policy: bounded migrations pass, migration storm fails", () => {
  const ok = libCall(`check_migrations '${join(FIXTURES, "perf-stat-placement.csv")}' 5`);
  assert.equal(ok.status, 0, ok.stderr);
  assert.match(ok.stdout, /migrations=12/);
  const storm = libCall(`check_migrations '${join(FIXTURES, "perf-stat-many-migrations.csv")}' 5`);
  assert.notEqual(storm.status, 0);
  assert.match(storm.stderr, /exceeds the publication policy/);
});

// --- command construction ------------------------------------------------------

const CMD_ENV = {
  EV_CPUS: "2,4",
  EV_JAR: "/x/benchmarks.jar",
  EV_SELECTOR: "pl.kzybala.lab.falsesharing.FalseSharingLinuxEvidenceBenchmark",
  EV_JVM_ARGS: "-Xms1g -Xmx1g -XX:+UseParallelGC",
  EV_THREADS: "2",
  EV_FORKS: "5",
  EV_WI: "5",
  EV_W: "1s",
  EV_I: "10",
  EV_R: "1s",
  EV_PERF_EVENTS: "cycles,instructions,cache-misses",
};

test("commands: JMH evidence run pins the explicit CPUs and selects exactly one layout", () => {
  const shared = libCall(`build_jmh_evidence_command shared /out/shared`, CMD_ENV).stdout.trim();
  assert.match(shared, /taskset -c 2,4/);
  assert.match(shared, /-p layout=shared/);
  assert.doesNotMatch(shared, /layout=padded/);
  assert.match(shared, /-t 2 -f 5 -wi 5 -w 1s -i 10 -r 1s/);
  assert.match(shared, /-rf json -rff \/out\/shared\/jmh\.json/);
  assert.match(shared, /perf stat -x, -e cpu-migrations,context-switches,task-clock/);
  const padded = libCall(`build_jmh_evidence_command padded /out/padded`, CMD_ENV).stdout.trim();
  assert.match(padded, /-p layout=padded/);
  assert.match(padded, /\/out\/padded\/jmh\.json/);
});

test("commands: perf stat repetitions use single-fork runs with matched JMH output per repetition", () => {
  const r1 = libCall(`build_perf_stat_command shared /out/shared 1`, CMD_ENV).stdout.trim();
  assert.match(r1, /perf stat -x, -e cycles,instructions,cache-misses -o \/out\/shared\/perf-stat\.csv/);
  assert.match(r1, /-f 1 /);
  assert.match(r1, /-rff \/out\/shared\/perf-stat-jmh\.json/);
  const r2 = libCall(`build_perf_stat_command shared /out/shared 2`, CMD_ENV).stdout.trim();
  assert.match(r2, /perf-stat-r2\.csv/);
  assert.match(r2, /perf-stat-jmh-r2\.json/);
});

test("commands: perf c2c record preserves binary data and the report is deterministic stdio", () => {
  const rec = libCall(`build_c2c_record_command padded /out/padded`, CMD_ENV).stdout.trim();
  assert.match(rec, /perf c2c record -o \/out\/padded\/perf-c2c\.data -- taskset -c 2,4 java/);
  assert.match(rec, /-p layout=padded/);
  const rep = libCall(`build_c2c_report_command padded /out/padded`, CMD_ENV).stdout.trim();
  assert.equal(rep, "perf c2c report -i /out/padded/perf-c2c.data --stdio --show-all --call-graph none");
});

// --- hashing -------------------------------------------------------------------

test("hashing: write_sha256sums covers every file with correct digests", () => {
  const dir = mkdtempSync(join(tmpdir(), "ev-hash-"));
  try {
    mkdirSync(join(dir, "shared"));
    writeFileSync(join(dir, "a.json"), "{}\n");
    writeFileSync(join(dir, "shared", "jmh.json"), "[1,2]\n");
    const r = libCall(`write_sha256sums '${dir}'`);
    assert.equal(r.status, 0, r.stderr);
    const sums = readFileSync(join(dir, "SHA256SUMS"), "utf8").trim().split("\n");
    assert.equal(sums.length, 2);
    const expected = createHash("sha256").update("[1,2]\n").digest("hex");
    assert.ok(sums.some((l) => l.startsWith(expected) && l.endsWith("./shared/jmh.json")));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// --- runner preflight failure paths (stubbed PATH, no PMU, no fabricated perf) --

function makeStubDir(stubs) {
  const dir = mkdtempSync(join(tmpdir(), "ev-stubs-"));
  for (const [name, body] of Object.entries(stubs)) {
    const path = join(dir, name);
    writeFileSync(path, `#!/bin/sh\n${body}\n`);
    chmodSync(path, 0o755);
  }
  return dir;
}

const LSCPU_STUB = `cat '${LSCPU_FIXTURE}'`;

function runRunner(args, stubDir, outDir) {
  try {
    const stdout = execFileSync("bash", [RUNNER, "false-sharing", ...args, "--out", outDir], {
      env: { ...process.env, PATH: `${stubDir}:/usr/bin:/bin` },
      encoding: "utf8",
      cwd: ROOT,
      stdio: ["ignore", "pipe", "pipe"],
    });
    return { status: 0, stdout, stderr: "" };
  } catch (err) {
    return { status: err.status ?? 1, stdout: err.stdout?.toString() ?? "", stderr: err.stderr?.toString() ?? "" };
  }
}

test("runner: fails before measurement when perf is missing", () => {
  const stubs = makeStubDir({
    uname: `echo Linux`,
    "systemd-detect-virt": `echo none`,
    lscpu: LSCPU_STUB,
  });
  const out = mkdtempSync(join(tmpdir(), "ev-out-"));
  try {
    const r = runRunner(["--profile", "publication", "--cpus", "0,1"], stubs, out);
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /perf is not installed/);
  } finally {
    rmSync(stubs, { recursive: true, force: true });
    rmSync(out, { recursive: true, force: true });
  }
});

test("runner: fails before measurement in an emulated/virtualized environment", () => {
  const stubs = makeStubDir({
    uname: `echo Linux`,
    "systemd-detect-virt": `echo kvm`,
    lscpu: LSCPU_STUB,
  });
  const out = mkdtempSync(join(tmpdir(), "ev-out-"));
  try {
    const r = runRunner(["--profile", "publication", "--cpus", "0,1"], stubs, out);
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /virtualization\/emulation detected/);
  } finally {
    rmSync(stubs, { recursive: true, force: true });
    rmSync(out, { recursive: true, force: true });
  }
});

test("runner: fails before measurement when perf c2c lacks PMU support", () => {
  const stubs = makeStubDir({
    uname: `echo Linux`,
    "systemd-detect-virt": `echo none`,
    lscpu: LSCPU_STUB,
    perf: `case "$1" in
  --version) echo "perf version 6.8"; exit 0 ;;
  stat) exit 0 ;;
  c2c) echo "failed to open memory events" >&2; exit 1 ;;
esac
exit 0`,
  });
  const out = mkdtempSync(join(tmpdir(), "ev-out-"));
  try {
    const r = runRunner(["--profile", "publication", "--cpus", "0,1"], stubs, out);
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /perf c2c cannot open its required memory events/);
  } finally {
    rmSync(stubs, { recursive: true, force: true });
    rmSync(out, { recursive: true, force: true });
  }
});

test("runner: fails before measurement when the correctness gate fails", () => {
  const stubs = makeStubDir({
    uname: `echo Linux`,
    "systemd-detect-virt": `echo none`,
    lscpu: LSCPU_STUB,
    perf: `case "$1" in
  --version) echo "perf version 6.8"; exit 0 ;;
  stat) exit 0 ;;
  c2c) echo "ldlat-loads, ldlat-stores"; exit 0 ;;
esac
exit 0`,
    mvn: `echo "simulated test failure" >&2; exit 1`,
    nproc: `echo 8`,
  });
  const out = mkdtempSync(join(tmpdir(), "ev-out-"));
  try {
    const r = runRunner(["--profile", "publication", "--cpus", "0,1"], stubs, out);
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /correctness gate failed/);
    // and nothing was measured: no variant directories were created
    assert.equal(existsSync(join(out, "false-sharing")), true);
    const runDirs = execSync(`find '${out}' -name jmh.json`, { encoding: "utf8" }).trim();
    assert.equal(runDirs, "");
  } finally {
    rmSync(stubs, { recursive: true, force: true });
    rmSync(out, { recursive: true, force: true });
  }
});

test("runner: rejects SMT siblings through the full CLI path", () => {
  const stubs = makeStubDir({
    uname: `echo Linux`,
    "systemd-detect-virt": `echo none`,
    lscpu: LSCPU_STUB,
  });
  const out = mkdtempSync(join(tmpdir(), "ev-out-"));
  try {
    const r = runRunner(["--dry-run", "--cpus", "0,2"], stubs, out);
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /SMT siblings|CPU selection rejected/);
  } finally {
    rmSync(stubs, { recursive: true, force: true });
    rmSync(out, { recursive: true, force: true });
  }
});

test("runner: dry-run produces the artifact skeleton and fully separated per-variant plans", () => {
  const stubs = makeStubDir({
    lscpu: LSCPU_STUB,
    "systemd-detect-virt": `echo none`,
  });
  const out = mkdtempSync(join(tmpdir(), "ev-out-"));
  try {
    const r = runRunner(["--profile", "publication", "--cpus", "0,1", "--dry-run"], stubs, out);
    assert.equal(r.status, 0, r.stderr);

    // planned commands: one JMH + three perf stat + one c2c record/report per variant
    for (const variant of ["shared", "padded"]) {
      assert.match(r.stdout, new RegExp(`\\[plan\\] perf stat .* -p layout=${variant} .*jmh\\.json`));
      assert.match(r.stdout, new RegExp(`\\[plan\\] perf c2c record -o .*${variant}/perf-c2c\\.data`));
    }
    // strict separation: no command names both layouts
    for (const line of r.stdout.split("\n").filter((l) => l.startsWith("[plan]"))) {
      assert.ok(!(line.includes("layout=shared") && line.includes("layout=padded")), line);
    }

    // metadata skeleton exists
    const runDir = execSync(`find '${out}/false-sharing' -mindepth 1 -maxdepth 1 -type d`, { encoding: "utf8" }).trim();
    for (const f of ["environment.json", "topology.txt", "capabilities.json", "toolchain.json", "benchmark-profile.json", "correctness.json", "evidence-manifest.json"]) {
      assert.ok(existsSync(join(runDir, f)), `missing ${f}`);
    }
    const profile = JSON.parse(readFileSync(join(runDir, "benchmark-profile.json"), "utf8"));
    assert.equal(profile.jmh.forks, 5);
    assert.equal(profile.jmh.threads, 2);
    assert.equal(profile.placement.cpus, "0,1");
    const manifest = JSON.parse(readFileSync(join(runDir, "evidence-manifest.json"), "utf8"));
    assert.equal(manifest.canonical.pendingImport, true);
    assert.equal(manifest.review.importDoesNotPromote, true);
  } finally {
    rmSync(stubs, { recursive: true, force: true });
    rmSync(out, { recursive: true, force: true });
  }
});

// --- virtualization detection (exit status is the source of truth) ---------------

// systemd-detect-virt stub factory: behavior per flag, with controlled
// stdout AND exit codes — physical hosts print "none" while exiting 1.
function virtStub({ vm, container }) {
  // vm/container: { out: string|null, code: number }
  return `case "$*" in
  *--vm*--quiet*) exit ${vm.code} ;;
  *--container*--quiet*) exit ${container.code} ;;
  *--vm*) ${vm.out === null ? ":" : `echo ${vm.out}`}; exit ${vm.code} ;;
  *--container*) ${container.out === null ? ":" : `echo ${container.out}`}; exit ${container.code} ;;
  *) ${vm.out === null ? ":" : `echo ${vm.out}`}; exit ${vm.code} ;;
esac`;
}

function detectWith(stubBody) {
  const stubs = makeStubDir({ "systemd-detect-virt": stubBody });
  try {
    return libCall("detect_virtualization", { PATH: `${stubs}:/usr/bin:/bin` });
  } finally {
    rmSync(stubs, { recursive: true, force: true });
  }
}

test("virt: VM check printing 'none' with exit 1 is a physical host, accepted", () => {
  const r = detectWith(virtStub({ vm: { out: "none", code: 1 }, container: { out: null, code: 1 } }));
  assert.notEqual(r.status, 0);
  assert.equal(r.stdout.trim(), "");
});

test("virt: container check printing 'none' with exit 1 is a physical host, accepted", () => {
  const r = detectWith(virtStub({ vm: { out: null, code: 1 }, container: { out: "none", code: 1 } }));
  assert.notEqual(r.status, 0);
  assert.equal(r.stdout.trim(), "");
});

test("virt: both checks printing 'none' with exit 1 are a physical host, accepted", () => {
  const r = detectWith(virtStub({ vm: { out: "none", code: 1 }, container: { out: "none", code: 1 } }));
  assert.notEqual(r.status, 0);
  assert.equal(r.stdout.trim(), "");
});

test("virt: the literal 'none' is never classified as virtualization even with exit 0", () => {
  const r = detectWith(virtStub({ vm: { out: "none", code: 0 }, container: { out: "none", code: 0 } }));
  assert.notEqual(r.status, 0);
  assert.equal(r.stdout.trim(), "");
});

test("virt: 'kvm' with exit 0 is detected as a VM", () => {
  const r = detectWith(virtStub({ vm: { out: "kvm", code: 0 }, container: { out: null, code: 1 } }));
  assert.equal(r.status, 0);
  assert.equal(r.stdout.trim(), "vm=kvm container=none");
});

test("virt: 'docker' with exit 0 is detected as a container", () => {
  const r = detectWith(virtStub({ vm: { out: null, code: 1 }, container: { out: "docker", code: 0 } }));
  assert.equal(r.status, 0);
  assert.equal(r.stdout.trim(), "vm=none container=docker");
});

// --- runner-level virtualization policy + preflight-only --------------------------

const PHYSICAL_VIRT = virtStub({ vm: { out: "none", code: 1 }, container: { out: "none", code: 1 } });
const KVM_VIRT = virtStub({ vm: { out: "kvm", code: 0 }, container: { out: null, code: 1 } });
const DOCKER_VIRT = virtStub({ vm: { out: null, code: 1 }, container: { out: "docker", code: 0 } });
const PERF_OK = `case "$1" in
  --version) echo "perf version 6.8"; exit 0 ;;
  stat) exit 0 ;;
  c2c) echo "ldlat-loads, ldlat-stores"; exit 0 ;;
esac
exit 0`;

function preflightStubs(virtBody) {
  return makeStubDir({
    uname: `echo Linux`,
    "systemd-detect-virt": virtBody,
    lscpu: LSCPU_STUB,
    perf: PERF_OK,
    mvn: `exit 0`,
    java: `exit 0`,
    nproc: `echo 8`,
  });
}

test("runner: physical host (none/exit-1) passes preflight for the publication profile", () => {
  const stubs = preflightStubs(PHYSICAL_VIRT);
  const out = mkdtempSync(join(tmpdir(), "ev-out-"));
  try {
    const r = runRunner(["--profile", "publication", "--cpus", "0,1", "--preflight-only"], stubs, out);
    assert.equal(r.status, 0, r.stderr);
    assert.match(r.stdout, /Preflight passed\./);
    assert.match(r.stdout, /Host type: physical/);
    assert.match(r.stdout, /Publication profile eligible: yes/);
    assert.match(r.stdout, /Selected CPUs: 0,1/);
    assert.match(r.stdout, /Measurement was not started because --preflight-only was supplied\./);
    const runDir = execSync(`find '${out}/false-sharing' -mindepth 1 -maxdepth 1 -type d`, { encoding: "utf8" }).trim();
    const caps = JSON.parse(readFileSync(join(runDir, "capabilities.json"), "utf8"));
    assert.deepEqual(caps.virtualization, {
      detected: false, vmType: null, containerType: null, environmentKind: "physical", publicationEligible: true,
    });
  } finally {
    rmSync(stubs, { recursive: true, force: true });
    rmSync(out, { recursive: true, force: true });
  }
});

test("runner: VM (kvm, exit 0) is rejected for publication", () => {
  const stubs = preflightStubs(KVM_VIRT);
  const out = mkdtempSync(join(tmpdir(), "ev-out-"));
  try {
    const r = runRunner(["--profile", "publication", "--cpus", "0,1"], stubs, out);
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /virtualization\/emulation detected \(vm=kvm container=none\)/);
  } finally {
    rmSync(stubs, { recursive: true, force: true });
    rmSync(out, { recursive: true, force: true });
  }
});

test("runner: container (docker, exit 0) is rejected for publication", () => {
  const stubs = preflightStubs(DOCKER_VIRT);
  const out = mkdtempSync(join(tmpdir(), "ev-out-"));
  try {
    const r = runRunner(["--profile", "publication", "--cpus", "0,1"], stubs, out);
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /virtualization\/emulation detected \(vm=none container=docker\)/);
  } finally {
    rmSync(stubs, { recursive: true, force: true });
    rmSync(out, { recursive: true, force: true });
  }
});

test("runner: --allow-virtualized with smoke profile is allowed but never publication eligible", () => {
  const stubs = preflightStubs(KVM_VIRT);
  const out = mkdtempSync(join(tmpdir(), "ev-out-"));
  try {
    const r = runRunner(["--profile", "smoke", "--cpus", "0,1", "--allow-virtualized", "--preflight-only"], stubs, out);
    assert.equal(r.status, 0, r.stderr);
    assert.match(r.stdout, /Host type: virtualized/);
    assert.match(r.stdout, /Publication profile eligible: no/);
    const runDir = execSync(`find '${out}/false-sharing' -mindepth 1 -maxdepth 1 -type d`, { encoding: "utf8" }).trim();
    const caps = JSON.parse(readFileSync(join(runDir, "capabilities.json"), "utf8"));
    assert.equal(caps.virtualization.publicationEligible, false);
    assert.equal(caps.virtualization.environmentKind, "virtualized");
    assert.equal(caps.virtualization.vmType, "kvm");
  } finally {
    rmSync(stubs, { recursive: true, force: true });
    rmSync(out, { recursive: true, force: true });
  }
});

test("runner: --allow-virtualized is rejected with the publication profile", () => {
  const stubs = preflightStubs(KVM_VIRT);
  const out = mkdtempSync(join(tmpdir(), "ev-out-"));
  try {
    const r = runRunner(["--profile", "publication", "--cpus", "0,1", "--allow-virtualized"], stubs, out);
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /--allow-virtualized cannot be used with profile 'publication'/);
  } finally {
    rmSync(stubs, { recursive: true, force: true });
    rmSync(out, { recursive: true, force: true });
  }
});

test("runner: --preflight-only runs validation but starts no JMH/perf collection and writes no archive", () => {
  const stubs = preflightStubs(PHYSICAL_VIRT);
  const out = mkdtempSync(join(tmpdir(), "ev-out-"));
  try {
    const r = runRunner(["--profile", "publication", "--cpus", "0,1", "--preflight-only"], stubs, out);
    assert.equal(r.status, 0, r.stderr);
    // no measurement artifacts of any kind
    assert.equal(execSync(`find '${out}' -name 'jmh*.json' -o -name 'perf-*'`, { encoding: "utf8" }).trim(), "");
    // no evidence archive
    assert.equal(execSync(`find '${out}' -name '*.tar.zst' -o -name '*.tar.gz'`, { encoding: "utf8" }).trim(), "");
    // and no claim of performance validation — only preflight
    assert.doesNotMatch(r.stdout, /DONE|Evidence archive/);
  } finally {
    rmSync(stubs, { recursive: true, force: true });
    rmSync(out, { recursive: true, force: true });
  }
});

test("runner: unknown options still fail", () => {
  const stubs = preflightStubs(PHYSICAL_VIRT);
  const out = mkdtempSync(join(tmpdir(), "ev-out-"));
  try {
    const r = runRunner(["--bogus"], stubs, out);
    assert.equal(r.status, 2);
    assert.match(r.stderr, /unknown option: --bogus/);
  } finally {
    rmSync(stubs, { recursive: true, force: true });
    rmSync(out, { recursive: true, force: true });
  }
});

// --- evidence-maturity invariants ------------------------------------------------

test("capability: perf and jmh importers remain fixture-only until real-host artifacts pass review", () => {
  assert.equal(IMPORTER_CAPABILITIES["perf-counter-importer"], "fixture-only");
  assert.equal(IMPORTER_CAPABILITIES["jmh-importer"], "fixture-only");
});

// --- measurement-environment policy (docs/measurement-environments.md) -----------

// A canonical-record factory shaped like a real import, with evidence
// dimensions controllable per test.
function madeRecord(evidenceOverrides = {}, provenanceOverrides = {}) {
  const [record] = importPerfCounterCsv("100,,cycles,1000,100.00,,\n", {
    labId: "false-sharing",
    variant: "shared",
    language: "java",
    sourceRevision: "b".repeat(40),
    environmentManifest: { ref: "results/x/environment.json", hash: "c".repeat(64) },
    evidence: {
      correctness: "passed",
      profiling: "present",
      comparability: "not-applicable",
      reproduction: { required: 1, completed: 0 },
      environment: "native-uncontrolled",
      ...evidenceOverrides,
    },
    ...provenanceOverrides,
  });
  return record;
}

// Scalar-record factory (render/regression only accept metricKind
// "scalar"): a legacy record cloned into a fresh, non-legacy shape with
// controllable evidence.
function madeScalarRecord(evidenceOverrides = {}) {
  const record = structuredClone(legacyResultsFor("false-sharing")[0]);
  record.evidence = {
    legacy: false,
    correctness: "passed",
    environment: "native-uncontrolled",
    reproduction: { required: 1, completed: 0 },
    profiling: "present",
    comparability: "not-applicable",
    reviewer: null,
    importerCapability: "fixture-only",
    warnings: [],
    ...evidenceOverrides,
  };
  record.provenance.environmentManifest = { ref: "results/x/environment.json", hash: "c".repeat(64) };
  record.provenance.capturedAt = "2026-07-15T00:00:00Z";
  return record;
}

test("policy: a developer-workstation result never renders a canonical Measured/Verified badge", () => {
  const devRecord = madeScalarRecord({ environment: "native-uncontrolled" });
  const [row] = renderTable([devRecord]);
  assert.equal(row.evidenceMaturity, "draft");
  assert.equal(row.isPublishable, false);
  assert.doesNotMatch(row.badge, /^(measured|verified)/i);

  const [legacyRow] = renderTable([legacyResultsFor("false-sharing")[0]]);
  assert.equal(legacyRow.evidenceMaturity, "legacy-unprovenanced");
  assert.doesNotMatch(legacyRow.badge, /^(measured|verified)/i);
  assert.match(legacyRow.badge, /cannot be a regression baseline/);
});

test("policy: a developer-workstation result can never become verified", () => {
  const devRecord = madeRecord({
    environment: "native-uncontrolled",
    reproduction: { required: 1, completed: 5 },
    reviewer: { name: "someone", reviewedAt: "2026-07-15T00:00:00Z" },
  });
  const maturity = deriveMaturity(devRecord);
  assert.notEqual(maturity.level, "verified");
  assert.equal(maturity.checks.environment, false);
});

test("policy: a developer-workstation result is never selected as a regression baseline", () => {
  const baselineCandidate = madeScalarRecord({ environment: "native-uncontrolled", reproduction: { required: 1, completed: 2 } });
  baselineCandidate.provenance.capturedAt = "2026-07-10T00:00:00Z";
  const newRecord = madeScalarRecord({ environment: "native-uncontrolled", reproduction: { required: 1, completed: 2 } });
  const result = compareToHistory(newRecord, [baselineCandidate]);
  assert.equal(result.baseline, null);
  assert.equal(result.status, "insufficient-history");
});

test("policy: Mac and Linux results cannot be directly compared", () => {
  const mac = madeRecord({ environment: "native-uncontrolled" });
  const linux = madeRecord({ environment: "native-controlled" });
  assert.equal(environmentClassOf(mac), "developer-workstation");
  assert.equal(environmentClassOf(linux), "native-linux-host");
  const verdict = canCompare(mac, linux);
  assert.equal(verdict.allowed, false);
  assert.ok(verdict.reasons.some((r) => r.includes("environment classes differ")));
});

test("policy: legacy records and different environment manifests are rejected from comparisons", () => {
  const legacy = legacyResultsFor("false-sharing")[0];
  const fresh = madeRecord({ environment: "native-controlled" });
  assert.equal(canCompare(legacy, fresh).allowed, false);

  const runA = madeRecord({ environment: "native-controlled" }, { environmentManifest: { ref: "a", hash: "d".repeat(64) } });
  const runB = madeRecord({ environment: "native-controlled" }, { environmentManifest: { ref: "b", hash: "e".repeat(64) } });
  const verdict = canCompare(runA, runB);
  assert.equal(verdict.allowed, false);
  assert.ok(verdict.reasons.some((r) => r.includes("environment manifests differ")));

  const sameManifest = madeRecord({ environment: "native-controlled" });
  const sameManifest2 = madeRecord({ environment: "native-controlled" });
  assert.equal(canCompare(sameManifest, sameManifest2).allowed, true);
});

test("policy: no lab page or manifest still carries a bare Measured status for Mac results", () => {
  for (const lab of ["cache-hierarchy", "cas-contention", "false-sharing", "spsc-ring-buffer", "thread-per-core"]) {
    const md = readFileSync(join(ROOT, "content", "labs", lab, "benchmark.md"), "utf8");
    assert.ok(!md.includes('disclosure-kind">Measured<'), `${lab}/benchmark.md still renders a bare Measured badge`);
    assert.match(md, /Illustrative development run/, `${lab}/benchmark.md missing the canonical development-run label`);
    assert.match(md, /Awaiting native-Linux measurement/, `${lab}/benchmark.md missing the awaiting state`);
    const page = readFileSync(join(ROOT, "lab", lab, "index.html"), "utf8");
    assert.ok(!page.includes('disclosure-kind">Measured<'), `lab/${lab} page still renders a bare Measured badge`);
    assert.match(page, /Illustrative development run/);
    assert.match(page, /awaiting native-Linux measurement/i);
  }
});

test("policy: the awaiting state renders no placeholder canonical numbers", () => {
  for (const lab of ["cache-hierarchy", "cas-contention", "false-sharing", "spsc-ring-buffer", "thread-per-core"]) {
    const md = readFileSync(join(ROOT, "content", "labs", lab, "benchmark.md"), "utf8");
    const section = md.split("## Canonical results")[1]?.split(/\n## /)[0] ?? "";
    assert.ok(section.length > 0, `${lab}: no Canonical results section`);
    assert.match(section, /Awaiting native-Linux measurement/);
    assert.ok(!/\|\s*[\d,.]+\s*\|/.test(section), `${lab}: canonical section must not contain placeholder result tables`);
  }
});

test("policy: importing one valid Linux run advances only to measured — reproduction and review still required for verified", () => {
  // Shaped like import-linux-evidence.mjs output for a first controlled run:
  // correctness passed, profiling present, native-controlled, but zero
  // completed reproductions, no reviewer, fixture-only importer capability.
  const firstImport = madeRecord({ environment: "native-controlled", reproduction: { required: 1, completed: 0 } });
  const maturity = deriveMaturity(firstImport);
  assert.equal(maturity.level, "draft");
  assert.notEqual(maturity.level, "verified");

  // Even with reproduction satisfied, review + importer capability are
  // still unmet — a run count alone can never produce verified.
  const reproduced = madeRecord({ environment: "native-controlled", reproduction: { required: 1, completed: 2 } });
  const maturity2 = deriveMaturity(reproduced);
  assert.notEqual(maturity2.level, "verified");
  assert.equal(maturity2.checks.reviewer, false);
  assert.equal(maturity2.checks.importerCapability, false);
});

test("maturity: a fixture-derived record can never reach verified, even with every other box checked", () => {
  const [record] = importPerfCounterCsv("100,,cycles,1000,100.00,,\n", {
    labId: "false-sharing",
    variant: "shared",
    language: "java",
    sourceRevision: "a".repeat(40),
    evidence: {
      correctness: "passed",
      environment: "native-controlled",
      reproduction: { required: 1, completed: 1 },
      profiling: "present",
      comparability: "not-applicable",
      reviewer: { name: "someone", reviewedAt: "2026-07-15T00:00:00Z" },
    },
  });
  const maturity = deriveMaturity(record);
  assert.notEqual(maturity.level, "verified");
  assert.equal(maturity.checks.importerCapability, false);
});
