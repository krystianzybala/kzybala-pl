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
  const shared = libCall(`build_jmh_evidence_command shared /out/shared`, { ...CMD_ENV, EV_JMH_EXTRA: "-p layout=shared" }).stdout.trim();
  assert.match(shared, /taskset -c 2,4/);
  assert.match(shared, /-p layout=shared/);
  assert.doesNotMatch(shared, /layout=padded/);
  assert.match(shared, /-t 2 -f 5 -wi 5 -w 1s -i 10 -r 1s/);
  assert.match(shared, /-rf json -rff \/out\/shared\/jmh\.json/);
  assert.match(shared, /perf stat -x, -e cpu-migrations,context-switches,task-clock/);
  const padded = libCall(`build_jmh_evidence_command padded /out/padded`, { ...CMD_ENV, EV_JMH_EXTRA: "-p layout=padded" }).stdout.trim();
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
  const rec = libCall(`build_c2c_record_command padded /out/padded`, { ...CMD_ENV, EV_JMH_EXTRA: "-p layout=padded" }).stdout.trim();
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

// Three independent toolchain/correctness failure paths — a stub set with
// no detectable java tests Java *discovery*, not the correctness gate, so
// each case controls exactly the layer it is about.
const GATE_PERF_STUB = `case "$1" in
  --version) echo "perf version 6.8"; exit 0 ;;
  stat) exit 0 ;;
  c2c) echo "ldlat-loads, ldlat-stores"; exit 0 ;;
esac
exit 0`;

function gateStubs({ java, mvn }) {
  const stubs = {
    uname: `echo Linux`,
    "systemd-detect-virt": `echo none`,
    lscpu: LSCPU_STUB,
    perf: GATE_PERF_STUB,
    nproc: `echo 8`,
  };
  if (java !== null) stubs.java = java;
  if (mvn !== null) stubs.mvn = mvn;
  return makeStubDir(stubs);
}

test("runner: missing Java fails at Java discovery, before the correctness gate", () => {
  // A stub that exists but cannot run -version shadows any host java shim
  // (/usr/bin/java on macOS) — "missing" means "not runnable", hermetically.
  const stubs = gateStubs({ java: `echo "no java runtime" >&2; exit 127`, mvn: `exit 0` });
  const out = mkdtempSync(join(tmpdir(), "ev-out-"));
  try {
    const r = runRunner(["--profile", "publication", "--cpus", "0,1"], stubs, out);
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /java is not installed/);
    assert.doesNotMatch(r.stderr, /correctness gate failed/);
  } finally {
    rmSync(stubs, { recursive: true, force: true });
    rmSync(out, { recursive: true, force: true });
  }
});

test("runner: fails before measurement when the correctness gate fails (Java present)", () => {
  const stubs = gateStubs({ java: `exit 0`, mvn: `echo "simulated test failure" >&2; exit 1` });
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

test("runner: Java present and correctness passing continues past the gate (preflight completes)", () => {
  const stubs = gateStubs({ java: `exit 0`, mvn: `exit 0` });
  const out = mkdtempSync(join(tmpdir(), "ev-out-"));
  try {
    const r = runRunner(["--profile", "publication", "--cpus", "0,1", "--preflight-only"], stubs, out);
    assert.equal(r.status, 0, r.stderr);
    assert.match(r.stdout, /Preflight passed\./);
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
    assert.deepEqual(profile.jmh.threadsPerScenario, { shared: 2, padded: 2 });
    assert.equal(profile.placement.cpus, "0,1");
    assert.deepEqual(profile.placement.scenarioCpuMapping, { shared: "0,1", padded: "0,1" });
    assert.match(profile.jvm.argsBase, /UseSerialGC/);
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

// --- per-lab evidence configurations ----------------------------------------------

const LABS_DIR = join(ROOT, "scripts", "performance-lab", "labs");

function confCall(lab, script, cpus = "2,4") {
  const cpuArray = cpus.split(",").join(" ");
  return libCall(`CPU_LIST=(${cpuArray}); source '${join(LABS_DIR, `${lab}.conf`)}'; ${script}`);
}

test("configs: the runner rejects unsupported lab ids", () => {
  const stubs = makeStubDir({ lscpu: LSCPU_STUB });
  const out = mkdtempSync(join(tmpdir(), "ev-out-"));
  try {
    const r = (() => {
      try {
        const stdout = execFileSync("bash", [RUNNER, "no-such-lab", "--cpus", "0,1", "--out", out], {
          env: { ...process.env, PATH: `${stubs}:/usr/bin:/bin` }, encoding: "utf8", cwd: ROOT, stdio: ["ignore", "pipe", "pipe"],
        });
        return { status: 0, stdout, stderr: "" };
      } catch (err) {
        return { status: err.status ?? 1, stdout: err.stdout?.toString() ?? "", stderr: err.stderr?.toString() ?? "" };
      }
    })();
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /unsupported lab id 'no-such-lab'/);
    assert.match(r.stderr, /false-sharing/);
  } finally {
    rmSync(stubs, { recursive: true, force: true });
    rmSync(out, { recursive: true, force: true });
  }
});

test("configs: SPSC scenarios cover cursor mode, batch and capacity, each in a JMH view AND a finite-harness view", () => {
  const variants = confCall("spsc-ring-buffer", "lab_variants").stdout.trim().split(/\s+/);
  // post batch-20260717T150131Z: every matrix case carries two views — the
  // Control-bounded JMH cost benchmark and the finite transfer harness
  // (the primary items/s source, which cannot hang by construction).
  assert.deepEqual(variants, [
    "cached-b1-c1024", "harness-cached-b1-c1024",
    "uncached-b1-c1024", "harness-uncached-b1-c1024",
    "cached-b64-c1024", "harness-cached-b64-c1024",
    "cached-b1-c65536", "harness-cached-b1-c65536",
  ]);
  assert.equal(confCall("spsc-ring-buffer", "lab_variant_kind harness-cached-b1-c1024").stdout.trim(), "aux");
  assert.equal(confCall("spsc-ring-buffer", "lab_variant_kind cached-b1-c1024").stdout.trim(), "jmh");
  const args = confCall("spsc-ring-buffer", "lab_jmh_args uncached-b1-c1024").stdout.trim();
  assert.equal(args, "-p cursorMode=uncached -p batch=1 -p capacity=1024");
  const harness = confCall("spsc-ring-buffer", "lab_aux_evidence_cmd harness-cached-b64-c1024").stdout.trim();
  assert.match(harness, /SpscTransferHarness/);
  assert.match(harness, /--capacity 1024 --cursor-mode cached --batch 64/);
  assert.match(harness, /--deadline-seconds \d+/);
  const rust = confCall("spsc-ring-buffer", "lab_rust_evidence_cmd cached-b64-c65536").stdout.trim();
  // Methodology parity: the publication harness is the persistent-worker
  // bin — never the Criterion spawn/join lifecycle benchmark.
  assert.match(rust, /--bin spsc_evidence/);
  assert.doesNotMatch(rust, /cargo bench/);
  assert.match(rust, /--cpus 2,4/);
  // harness variants never duplicate the Rust run
  assert.equal(confCall("spsc-ring-buffer", "lab_rust_evidence_cmd harness-cached-b1-c1024").stdout.trim(), "");
});

test("configs: CAS contender scenarios derive from the validated CPU set, never hardcoded CPU numbers", () => {
  const two = confCall("cas-contention", "lab_variants", "2,4").stdout.trim().split(/\s+/);
  assert.ok(two.includes("cas-t2-none") && two.includes("single-writer"));
  assert.ok(!two.some((v) => v.includes("t4") || v.includes("t8")), "2 CPUs cannot host 4/8 contenders");
  const eight = confCall("cas-contention", "lab_variants", "1,2,3,4,5,6,7,8").stdout.trim().split(/\s+/);
  assert.ok(eight.includes("cas-t8-expjitter"));
  const props = confCall("cas-contention", "lab_worker_props cas-t4-none", "1,2,3,4,5,6,7,8").stdout.trim();
  assert.equal(props, "-Dplab.workerCpus=1,2,3,4", "worker CPUs are a deterministic prefix of the validated set");
  const threads = confCall("cas-contention", "lab_threads cas-t8-fixed").stdout.trim();
  assert.equal(threads, "8");
  assert.equal(confCall("cas-contention", "lab_threads single-writer").stdout.trim(), "1");
});

// --- rejected runs stay diagnostic-only --------------------------------------------

test("rejected runs: the importer refuses a run stamped rejected", () => {
  const dir = mkdtempSync(join(tmpdir(), "ev-rejected-"));
  try {
    writeFileSync(join(dir, "evidence-manifest.json"), JSON.stringify({ labId: "false-sharing", runId: "x", variants: { shared: {} } }));
    writeFileSync(join(dir, "environment.json"), JSON.stringify({ capturedAt: "2026-07-15T00:00:00Z" }));
    writeFileSync(join(dir, "benchmark-profile.json"), JSON.stringify({ placement: { cpus: "2,4" } }));
    writeFileSync(join(dir, "correctness.json"), JSON.stringify({ status: "passed" }));
    writeFileSync(join(dir, "run-status.json"), JSON.stringify({
      runStatus: "rejected", publicationEligible: false, canonicalEvidenceEligible: false,
      rejectionReason: "worker-placement-or-migration-policy",
    }));
    const r = (() => {
      try {
        const stdout = execFileSync("node", [join(ROOT, "scripts", "performance-lab", "import-linux-evidence.mjs"), dir], {
          encoding: "utf8", stdio: ["ignore", "pipe", "pipe"],
        });
        return { status: 0, stdout, stderr: "" };
      } catch (err) {
        return { status: err.status ?? 1, stdout: err.stdout?.toString() ?? "", stderr: err.stderr?.toString() ?? "" };
      }
    })();
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /rejected\/partial runs are diagnostic only/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("rejected runs: results/ and evidence archives are ignored by git", () => {
  const ignored = execSync("git check-ignore results/false-sharing/some-run/jmh.json results/false-sharing-x-linux-evidence.tar.zst || true", { cwd: ROOT, encoding: "utf8" });
  assert.match(ignored, /results\/false-sharing\/some-run\/jmh\.json/);
  assert.match(ignored, /linux-evidence\.tar\.zst/);
});

// --- single-core cardinality (real-host preflight regression) ---------------------
// The first Precision 5810 preflight blocked cache-hierarchy/jit-pipeline
// on an unconditional CPU_LIST[1] access. These tests run the REAL runner
// against the REAL shipped lab configs with a 5810-like topology fixture
// and stubbed tooling — fixture results, never a real-host success claim.

const LSCPU_5810 = join(FIXTURES, "lscpu-e-precision5810-like.txt");
const PREFLIGHT_STUBS_5810 = () => makeStubDir({
  uname: `echo Linux`,
  "systemd-detect-virt": `echo none`,
  lscpu: `cat '${LSCPU_5810}'`,
  perf: GATE_PERF_STUB,
  mvn: `exit 0`,
  java: `exit 0`,
  nproc: `echo 14`,
});

function runRealLab(lab, cpus, extraArgs, stubs, out) {
  try {
    const stdout = execFileSync("bash", [RUNNER, lab, "--profile", "publication", "--cpus", cpus, ...extraArgs, "--out", out], {
      env: { ...process.env, PATH: `${stubs}:/usr/bin:/bin` },
      encoding: "utf8", cwd: ROOT, stdio: ["ignore", "pipe", "pipe"],
    });
    return { status: 0, stdout, stderr: "" };
  } catch (err) {
    return { status: err.status ?? 1, stdout: err.stdout?.toString() ?? "", stderr: err.stderr?.toString() ?? "" };
  }
}

test("single-core: cache-hierarchy and jit-pipeline preflight with exactly one CPU, no CPU_LIST[1], no cpuB, single topology kind", () => {
  const stubs = PREFLIGHT_STUBS_5810();
  const out = mkdtempSync(join(tmpdir(), "ev-single-"));
  try {
    for (const lab of ["cache-hierarchy", "jit-pipeline"]) {
      const r = runRealLab(lab, "2", ["--preflight-only"], stubs, out);
      assert.equal(r.status, 0, `${lab}: ${r.stderr}`);
      assert.match(r.stdout, /Preflight passed\./);
      assert.doesNotMatch(r.stderr + r.stdout, /unbound variable/);
      // pair-topology validation never ran; single validation did
      assert.match(r.stdout, /kind=single cpu=2/);
      assert.doesNotMatch(r.stdout, /coreA=|SMT siblings|cross-socket/);
      const runDir = execSync(`find '${out}/${lab}' -mindepth 1 -maxdepth 1 -type d | tail -1`, { encoding: "utf8" }).trim();
      const envJson = readFileSync(join(runDir, "environment.json"), "utf8");
      assert.ok(!envJson.includes("cpuB"), `${lab}: environment.json must not carry a cpuB field`);
      const parsed = JSON.parse(envJson);
      assert.equal(parsed.selectedCpus.kind, "single");
      assert.equal(parsed.selectedCpus.list, "2");
      assert.equal(parsed.cpuDetails.length, 1, "exactly one selected CPU recorded");
      assert.equal(parsed.cpuDetails[0].cpu, 2);
    }
  } finally {
    rmSync(stubs, { recursive: true, force: true });
    rmSync(out, { recursive: true, force: true });
  }
});

test("single-core: command construction pins exactly 'taskset -c 2' with no trailing comma and no second worker", () => {
  const stubs = PREFLIGHT_STUBS_5810();
  const out = mkdtempSync(join(tmpdir(), "ev-single-dry-"));
  try {
    const r = runRealLab("cache-hierarchy", "2", ["--dry-run"], stubs, out);
    assert.equal(r.status, 0, r.stderr);
    const planLines = r.stdout.split("\n").filter((l) => l.includes("[plan]") && l.includes("taskset"));
    assert.ok(planLines.length > 0);
    for (const line of planLines) {
      assert.match(line, /taskset -c 2 /, line);
      assert.doesNotMatch(line, /taskset -c 2,/, "no fake second CPU, no trailing comma");
      assert.doesNotMatch(line, /plab\.cpuB/, "no second-worker affinity property");
    }
  } finally {
    rmSync(stubs, { recursive: true, force: true });
    rmSync(out, { recursive: true, force: true });
  }
});

test("cardinality: every lab requires its exact CPU count — under- and over-supply fail with a clear error", () => {
  const stubs = PREFLIGHT_STUBS_5810();
  const out = mkdtempSync(join(tmpdir(), "ev-card-"));
  try {
    const cases = [
      // [lab, wrong cpus, expected exact]
      ["false-sharing", "2", 2],          // pair lab, one CPU
      ["spsc-ring-buffer", "2", 2],       // pair lab, one CPU
      ["thread-per-core", "2,3", 4],      // quad lab, two CPUs
      ["cas-contention", "2,3", 8],       // octet lab, two CPUs
      ["cache-hierarchy", "2,3", 1],      // single lab, two CPUs
      ["jit-pipeline", "2,3", 1],         // single lab, two CPUs
    ];
    for (const [lab, cpus, exact] of cases) {
      const r = runRealLab(lab, cpus, ["--preflight-only"], stubs, out);
      assert.notEqual(r.status, 0, `${lab} must reject --cpus ${cpus}`);
      assert.match(r.stderr, new RegExp(`cardinality error: lab ${lab} requires exactly ${exact} CPU`), r.stderr);
    }
  } finally {
    rmSync(stubs, { recursive: true, force: true });
    rmSync(out, { recursive: true, force: true });
  }
});

test("cardinality: all eight real lab configurations pass the full simulated live preflight at their exact cardinality", () => {
  const stubs = PREFLIGHT_STUBS_5810();
  const out = mkdtempSync(join(tmpdir(), "ev-all8-"));
  // preflight must never modify tracked files (source-integrity invariant)
  const porcelainBefore = execSync("git status --porcelain", { cwd: ROOT, encoding: "utf8" });
  try {
    const cpusFor = {
      "cache-hierarchy": "2", "jit-pipeline": "2",
      "false-sharing": "2,3", "spsc-ring-buffer": "2,3", "mesi": "2,3", "memory-ordering": "2,3",
      "thread-per-core": "2,3,4,5",
      "cas-contention": "2,3,4,5,6,7,8,9",
    };
    for (const [lab, cpus] of Object.entries(cpusFor)) {
      const r = runRealLab(lab, cpus, ["--preflight-only"], stubs, out);
      assert.equal(r.status, 0, `${lab} (--cpus ${cpus}): ${r.stderr || r.stdout}`);
      assert.match(r.stdout, /Preflight passed\./, lab);
    }
    const porcelainAfter = execSync("git status --porcelain", { cwd: ROOT, encoding: "utf8" });
    assert.equal(porcelainAfter, porcelainBefore, "simulated preflight of all eight labs must not modify any tracked file");
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
    // Public pages no longer render Mac numeric tables at all — the
    // internal benchmark.md retains them as development artifacts.
    assert.ok(!page.includes("Apple M1 Max"), `lab/${lab} page still renders developer-workstation results`);
    assert.match(page, /Awaiting native-Linux measurement/i);
    assert.ok(!page.includes("github.com/krystianzybala/kzybala-pl"), `lab/${lab} links the private repository`);
    assert.ok(!page.includes("site's repository"), `lab/${lab} still claims repository access`);
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

// --- hard wall-clock timeout (batch-20260717T150131Z SPSC hang regression) ---
// A JMH forked worker span 14h40m at 100% CPU because the producer loop had
// no termination bound. The runner must never depend on the benchmark being
// well-behaved again: every external invocation runs under run_with_deadline.

test("timeout lib: run_with_deadline passes a completing command's exit status through", () => {
  const r = libCall(`run_with_deadline 30 /tmp unit-pass 'exit 7'`);
  assert.equal(r.status, 7);
  const ok = libCall(`run_with_deadline 30 /tmp unit-pass 'true'`);
  assert.equal(ok.status, 0, ok.stderr);
});

test("timeout lib: run_with_deadline returns 124 on a hang, captures diagnostics, and leaves no survivors", () => {
  const dir = mkdtempSync(join(tmpdir(), "ev-deadline-"));
  const pidfile = join(dir, "hang.pid");
  try {
    const r = libCall(
      `run_with_deadline 1 '${dir}' unit-hang 'echo $$ > ${pidfile}; exec sleep 60'`,
      { PLAB_TIMEOUT_KILL_AFTER_SECONDS: "5" },
    );
    assert.equal(r.status, 124);
    assert.match(r.stderr, /TIMEOUT after 1s — unit-hang/);
    assert.doesNotMatch(r.stderr, /escalating to SIGKILL/);
    assert.ok(existsSync(join(dir, "timeout-process-tree.txt")), "process-tree snapshot missing");
    const diag = JSON.parse(readFileSync(join(dir, "timeout-diagnostics.json"), "utf8"));
    assert.equal(diag.capturedBeforeTermination, true);
    assert.match(diag.terminationPolicy, /SIGTERM/);
    const pid = Number(readFileSync(pidfile, "utf8").trim());
    assert.ok(pid > 0);
    assert.throws(() => process.kill(pid, 0), /ESRCH/, "hung child survived the deadline");
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// Full-runner hang: stub perf/taskset exec their payload so the stub java
// (which hangs exactly like the real SPSC ForkedMain did) actually runs
// under the runner's measurement path. One invocation, asserted from three
// angles below (classification, diagnostics, cleanup).
function hangStubs() {
  return makeStubDir({
    uname: `echo Linux`,
    "systemd-detect-virt": PHYSICAL_VIRT,
    lscpu: LSCPU_STUB,
    nproc: `echo 8`,
    perf: `if [ "$1" = "--version" ]; then echo "perf version 6.8"; exit 0; fi
if [ "$1" = "c2c" ]; then case "$*" in *"-e list"*) echo "ldlat-loads, ldlat-stores"; exit 0 ;; esac; fi
while [ $# -gt 0 ]; do
  if [ "$1" = "--" ]; then shift; exec "$@"; fi
  shift
done
exit 0`,
    taskset: `if [ "$1" = "-c" ]; then shift 2; exec "$@"; fi
exit 0`,
    mvn: `case "$*" in
  *package*) mkdir -p target; [ -f target/benchmarks.jar ] || : > target/benchmarks.jar ;;
esac
exit 0`,
    java: `case "$*" in
  *-version*) exit 0 ;;
  *benchmarks.jar*) echo $$ > "$PLAB_TEST_PIDFILE"; exec sleep 300 ;;
esac
exit 0`,
  });
}

let hangCache = null;
function hangRunResult() {
  if (hangCache) return hangCache;
  const pidfile = join(mkdtempSync(join(tmpdir(), "ev-pid-")), "hang.pid");
  const stubs = hangStubs();
  const out = mkdtempSync(join(tmpdir(), "ev-out-"));
  let r;
  try {
    const stdout = execFileSync(
      "bash",
      [RUNNER, "false-sharing", "--profile", "smoke", "--cpus", "0,1", "--skip-load-check", "--out", out],
      {
        env: {
          ...process.env,
          PATH: `${stubs}:/usr/bin:/bin`,
          PLAB_TIMEOUT_OVERRIDE_SECONDS: "2",
          PLAB_TIMEOUT_KILL_AFTER_SECONDS: "5",
          PLAB_TEST_PIDFILE: pidfile,
        },
        encoding: "utf8", cwd: ROOT, stdio: ["ignore", "pipe", "pipe"], timeout: 120000,
      },
    );
    r = { status: 0, stdout, stderr: "" };
  } catch (err) {
    r = { status: err.status ?? 1, stdout: err.stdout?.toString() ?? "", stderr: err.stderr?.toString() ?? "" };
  }
  const runDir = execSync(`find '${out}/false-sharing' -mindepth 1 -maxdepth 1 -type d`, { encoding: "utf8" }).trim();
  hangCache = { r, runDir, pidfile };
  return hangCache;
}

test("runner timeout: a hung benchmark is killed at the budget and classified failed-benchmark-timeout (exit 3)", () => {
  const { r, runDir } = hangRunResult();
  assert.equal(r.status, 3, r.stderr);
  assert.match(r.stderr, /BENCHMARK TIMEOUT/);
  assert.match(r.stderr, /DIAGNOSTIC ONLY/);
  const status = JSON.parse(readFileSync(join(runDir, "run-status.json"), "utf8"));
  assert.equal(status.runStatus, "failed-benchmark-timeout");
  assert.equal(status.publicationEligible, false);
  assert.equal(status.canonicalEvidenceEligible, false);
  assert.match(status.rejectionReason, /jmh-evidence \(variant shared\)/);
});

test("runner timeout: hang diagnostics are captured from the live tree before termination", () => {
  const { runDir } = hangRunResult();
  const vdir = join(runDir, "shared");
  assert.ok(existsSync(join(vdir, "timeout-process-tree.txt")), "timeout-process-tree.txt missing");
  const tree = readFileSync(join(vdir, "timeout-process-tree.txt"), "utf8");
  assert.match(tree, /sleep 300/, "snapshot must show the hung command while still alive");
  const diag = JSON.parse(readFileSync(join(vdir, "timeout-diagnostics.json"), "utf8"));
  assert.equal(diag.capturedBeforeTermination, true);
  assert.equal(diag.budgetSeconds, 2);
  assert.match(diag.terminationPolicy, /SIGKILL only after/);
});

test("runner timeout: SIGTERM cleans the whole tree (no SIGKILL, no survivors, no further variants)", () => {
  const { r, runDir, pidfile } = hangRunResult();
  assert.doesNotMatch(r.stderr, /escalating to SIGKILL/);
  const pid = Number(readFileSync(pidfile, "utf8").trim());
  assert.ok(pid > 0);
  assert.throws(() => process.kill(pid, 0), /ESRCH/, "hung benchmark process survived the runner");
  assert.equal(existsSync(join(runDir, "padded")), false, "runner must abort, not continue to the next variant");
});

// --- focused --variant runs + the SPSC core/sweep matrix ---------------------

function runLab(lab, args, stubDir, outDir, extraEnv = {}) {
  try {
    const stdout = execFileSync("bash", [RUNNER, lab, ...args, "--out", outDir], {
      env: { ...process.env, PATH: `${stubDir}:/usr/bin:/bin`, ...extraEnv },
      encoding: "utf8", cwd: ROOT, stdio: ["ignore", "pipe", "pipe"],
    });
    return { status: 0, stdout, stderr: "" };
  } catch (err) {
    return { status: err.status ?? 1, stdout: err.stdout?.toString() ?? "", stderr: err.stderr?.toString() ?? "" };
  }
}

test("runner --variant: an unknown variant is rejected with the available list", () => {
  const stubs = makeStubDir({ lscpu: LSCPU_STUB, "systemd-detect-virt": PHYSICAL_VIRT });
  const out = mkdtempSync(join(tmpdir(), "ev-out-"));
  try {
    const r = runLab("spsc-ring-buffer", ["--dry-run", "--cpus", "0,1", "--variant", "no-such-variant"], stubs, out);
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /unknown variant 'no-such-variant'/);
    assert.match(r.stderr, /cached-b1-c1024/);
  } finally {
    rmSync(stubs, { recursive: true, force: true });
    rmSync(out, { recursive: true, force: true });
  }
});

test("runner --variant: a focused run plans only the selected variant and is recorded as focused in the manifest", () => {
  const stubs = makeStubDir({ lscpu: LSCPU_STUB, "systemd-detect-virt": PHYSICAL_VIRT });
  const out = mkdtempSync(join(tmpdir(), "ev-out-"));
  try {
    const r = runLab("spsc-ring-buffer", ["--dry-run", "--profile", "smoke", "--cpus", "0,1", "--variant", "cached-b1-c1024"], stubs, out);
    assert.equal(r.status, 0, r.stderr);
    const plans = r.stdout.split("\n").filter((l) => l.startsWith("[plan]"));
    assert.ok(plans.length > 0);
    for (const line of plans) {
      assert.doesNotMatch(line, /uncached|65536|SpscTransferHarness/, line);
    }
    assert.ok(plans.some((l) => /cursorMode=cached -p batch=1 -p capacity=1024/.test(l)));
    const runDir = execSync(`find '${out}/spsc-ring-buffer' -mindepth 1 -maxdepth 1 -type d`, { encoding: "utf8" }).trim();
    const manifest = JSON.parse(readFileSync(join(runDir, "evidence-manifest.json"), "utf8"));
    assert.equal(manifest.variantSelection, "focused:cached-b1-c1024");
    assert.deepEqual(Object.keys(manifest.variants), ["cached-b1-c1024"]);
  } finally {
    rmSync(stubs, { recursive: true, force: true });
    rmSync(out, { recursive: true, force: true });
  }
});

test("spsc matrix: the default plan carries both views (JMH cost + finite harness) for every case incl. the sweep", () => {
  const stubs = makeStubDir({ lscpu: LSCPU_STUB, "systemd-detect-virt": PHYSICAL_VIRT });
  const out = mkdtempSync(join(tmpdir(), "ev-out-"));
  try {
    const r = runLab("spsc-ring-buffer", ["--dry-run", "--profile", "smoke", "--cpus", "0,1"], stubs, out);
    assert.equal(r.status, 0, r.stderr);
    // the previously hanging case is present in both views
    assert.match(r.stdout, /cursorMode=cached -p batch=1 -p capacity=1024/);
    assert.match(r.stdout, /SpscTransferHarness --items \d+ --capacity 1024 --cursor-mode cached --batch 1/);
    // capacity sweep is in the default matrix (the defect is never hidden by shrinking the matrix)
    assert.match(r.stdout, /capacity=65536/);
    assert.match(r.stdout, /SpscTransferHarness --items \d+ --capacity 65536/);
    const manifest = JSON.parse(readFileSync(
      join(execSync(`find '${out}/spsc-ring-buffer' -mindepth 1 -maxdepth 1 -type d`, { encoding: "utf8" }).trim(), "evidence-manifest.json"), "utf8"));
    assert.equal(manifest.variantSelection, "all");
    assert.equal(Object.keys(manifest.variants).length, 8);
  } finally {
    rmSync(stubs, { recursive: true, force: true });
    rmSync(out, { recursive: true, force: true });
  }
});

test("spsc matrix: PLAB_SPSC_MATRIX=core keeps all three core cases in both views and drops only the capacity sweep", () => {
  const stubs = makeStubDir({ lscpu: LSCPU_STUB, "systemd-detect-virt": PHYSICAL_VIRT });
  const out = mkdtempSync(join(tmpdir(), "ev-out-"));
  try {
    const r = runLab("spsc-ring-buffer", ["--dry-run", "--profile", "smoke", "--cpus", "0,1"], stubs, out, { PLAB_SPSC_MATRIX: "core" });
    assert.equal(r.status, 0, r.stderr);
    assert.match(r.stdout, /cursorMode=cached -p batch=1 -p capacity=1024/);
    assert.match(r.stdout, /cursorMode=uncached -p batch=1 -p capacity=1024/);
    assert.match(r.stdout, /cursorMode=cached -p batch=64 -p capacity=1024/);
    assert.match(r.stdout, /SpscTransferHarness --items \d+ --capacity 1024 --cursor-mode uncached/);
    assert.doesNotMatch(r.stdout, /65536/);
    const manifest = JSON.parse(readFileSync(
      join(execSync(`find '${out}/spsc-ring-buffer' -mindepth 1 -maxdepth 1 -type d`, { encoding: "utf8" }).trim(), "evidence-manifest.json"), "utf8"));
    assert.equal(Object.keys(manifest.variants).length, 6);
  } finally {
    rmSync(stubs, { recursive: true, force: true });
    rmSync(out, { recursive: true, force: true });
  }
});
