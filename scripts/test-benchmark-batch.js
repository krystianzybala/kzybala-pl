// Tests for the batch orchestrator (scripts/performance-lab/
// run-all-benchmarks.sh) and its verify/import companions. No benchmark or
// profiler ever executes: the per-lab runner and evidence verifier are
// substituted through the documented PLAB_* test hooks, and topology/host
// facts come from fixtures. No test fabricates a successful measurement's
// numeric content — stub archives carry marker files only.
import test from "node:test";
import assert from "node:assert/strict";
import { execFileSync, execSync } from "node:child_process";
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync, existsSync, readdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const ROOT = join(import.meta.dirname, "..");
const BATCH = join(ROOT, "scripts", "performance-lab", "run-all-benchmarks.sh");
const VERIFY_BATCH = join(ROOT, "scripts", "performance-lab", "verify-benchmark-batch.sh");
const LIB = join(ROOT, "scripts", "performance-lab", "lib", "evidence-lib.sh");

// --- fixture builders --------------------------------------------------------

function makeEnv() {
  const base = mkdtempSync(join(tmpdir(), "plab-batch-"));
  const content = join(base, "content");
  const confs = join(base, "confs");
  const batches = join(base, "batches");
  const bin = join(base, "bin");
  const log = join(base, "invocations.log");
  mkdirSync(content, { recursive: true });
  mkdirSync(confs, { recursive: true });
  mkdirSync(bin, { recursive: true });

  for (const lab of ["lab-a", "lab-b"]) {
    mkdirSync(join(content, lab), { recursive: true });
    writeFileSync(join(content, lab, "lab.json"), JSON.stringify({ id: lab, benchmark: true }));
    // referenced java project with a correctness test file
    const javaDir = join(base, "code", lab, "java");
    mkdirSync(join(javaDir, "src", "test", "java"), { recursive: true });
    writeFileSync(join(javaDir, "src", "test", "java", "T.java"), "class T {}");
    writeFileSync(join(confs, `${lab}.conf`), `#!/usr/bin/env bash
LAB_BENCHMARK_READY=1
LAB_MIN_CPUS=2
LAB_COOLDOWN_CLASS="short"
LAB_JAVA_DIR="${javaDir.slice(ROOT.length + 1) || javaDir}"
EV_SELECTOR="x"
lab_variants() { echo "v1"; }
lab_jmh_args() { echo "-p v=$1"; }
lab_threads() { echo 2; }
lab_cpu_count() { echo 2; }
lab_worker_props() { echo ""; }
lab_rust_evidence_cmd() { echo ""; }
`);
  }
  // LAB_JAVA_DIR is resolved against REPO_ROOT — use absolute-ish trick:
  // rewrite confs to point at the fixture dir via ../.. escape-free path.
  for (const lab of ["lab-a", "lab-b"]) {
    const javaDir = join(base, "code", lab, "java");
    let conf = readFileSync(join(confs, `${lab}.conf`), "utf8");
    conf = conf.replace(/LAB_JAVA_DIR=.*/, `LAB_JAVA_DIR="${relFromRoot(javaDir)}"`);
    writeFileSync(join(confs, `${lab}.conf`), conf);
  }

  // stub per-lab runner: logs, honors --preflight-only, simulates runs
  writeFileSync(join(bin, "stub-runner.sh"), `#!/usr/bin/env bash
echo "$(date +%s%N) INVOKE $*" >> "${log}"
lab="$1"; shift
preflight=0; out=""
while [ $# -gt 0 ]; do
  case "$1" in
    --preflight-only) preflight=1; shift ;;
    --out) out="$2"; shift 2 ;;
    *) shift ;;
  esac
done
safe="\${lab//-/_}"
if [ "$preflight" = "1" ]; then
  eval "exit \\\${STUB_PREFLIGHT_EXIT_\${safe}:-0}"
fi
countfile="${log}.count.\${lab}"
n=$(cat "$countfile" 2>/dev/null || echo 0); n=$((n+1)); echo "$n" > "$countfile"
echo "$(date +%s%N) MEASURE-START \${lab} rep\${n}" >> "${log}"
rc=$(eval echo "\\\${STUB_RUN_EXIT_\${safe}_\${n}:-0}")
if [ "$rc" != "0" ]; then
  mkdir -p "$out/\${lab}"
  echo diagnostic > "$out/\${lab}/rejected.txt"
  echo "$(date +%s%N) MEASURE-FAIL \${lab} rep\${n}" >> "${log}"
  exit "$rc"
fi
mkdir -p "$out/\${lab}/linux-fake-\${lab}-\${n}"
echo evidence-marker > "$out/\${lab}/linux-fake-\${lab}-\${n}/marker.txt"
tar -czf "$out/\${lab}-linux-fake\${n}-linux-evidence.tar.gz" -C "$out" "\${lab}"
echo "$(date +%s%N) MEASURE-END \${lab} rep\${n}" >> "${log}"
`);
  chmodSync(join(bin, "stub-runner.sh"), 0o755);
  writeFileSync(join(bin, "stub-verify.sh"), `#!/usr/bin/env bash\nexit 0\n`);
  chmodSync(join(bin, "stub-verify.sh"), 0o755);
  // clean-tree git (publication gate) — dirty-tree tests override this stub
  writeFileSync(join(bin, "git"), `#!/bin/sh
case "$*" in
  *status*--porcelain*) exit 0 ;;
  *rev-parse*) echo "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"; exit 0 ;;
  *log*) echo "{}"; exit 0 ;;
esac
exit 0
`);
  chmodSync(join(bin, "git"), 0o755);
  // Linux-faithful pgrep: no match prints "0" AND exits 1 (the real-host
  // double-zero regression source)
  writeFileSync(join(bin, "pgrep"), `#!/bin/sh\necho 0\nexit 1\n`);
  chmodSync(join(bin, "pgrep"), 0o755);
  writeFileSync(join(bin, "nproc"), `#!/bin/sh\necho 14\n`);
  chmodSync(join(bin, "nproc"), 0o755);
  writeFileSync(join(base, "loadavg-stable.txt"), "1.52 0.43 0.15 1/500 12345\n");

  writeFileSync(join(base, "host.yaml"), `schema_version: 1
host_name: test-host
max_load_per_core_x100: 20
stability_timeout_seconds: 30
stability_consecutive_samples: 3
stability_sample_interval_seconds: 0

cooldown_seconds:
  short: 0
  standard: 0
  long: 0

cpu_sets:
  pair: "0,1"
  single: "0"

labs:
  lab-a: pair
  lab-b: pair
`);
  return { base, content, confs, batches, bin, log, host: join(base, "host.yaml") };
}

function relFromRoot(abs) {
  // LAB_JAVA_DIR is joined to REPO_ROOT; escape the repo with ../ segments.
  const rootParts = ROOT.split("/").filter(Boolean);
  return "../".repeat(rootParts.length) + abs.replace(/^\//, "");
}

function runBatch(env, args, extraEnv = {}) {
  try {
    const stdout = execFileSync("bash", [BATCH, ...args], {
      env: {
        ...process.env,
        PATH: `${env.bin}:${process.env.PATH}`,
        PLAB_RUNNER_OVERRIDE: join(env.bin, "stub-runner.sh"),
        PLAB_VERIFY_OVERRIDE: join(env.bin, "stub-verify.sh"),
        PLAB_CONTENT_ROOT: env.content,
        PLAB_CONF_DIR: env.confs,
        PLAB_BATCH_ROOT: env.batches,
        PLAB_PROC_LOADAVG: join(env.base, "loadavg-stable.txt"),
        ...extraEnv,
      },
      encoding: "utf8",
      cwd: ROOT,
      stdio: ["ignore", "pipe", "pipe"],
    });
    return { status: 0, stdout, stderr: "" };
  } catch (err) {
    return { status: err.status ?? 1, stdout: err.stdout?.toString() ?? "", stderr: err.stderr?.toString() ?? "" };
  }
}

const BASE_ARGS = ["--profile", "publication", "--repetitions", "2"];

function readLog(env) {
  return existsSync(env.log) ? readFileSync(env.log, "utf8").trim().split("\n") : [];
}

function latestManifest(env) {
  const ids = readdirSync(env.batches).filter((d) => d.startsWith("batch-"));
  assert.ok(ids.length > 0, "no batch directory created");
  return JSON.parse(readFileSync(join(env.batches, ids.sort().pop(), "batch-manifest.json"), "utf8"));
}

// --- §15 cases ----------------------------------------------------------------

test("batch: every enabled lab is preflighted before any measurement starts", () => {
  const env = makeEnv();
  try {
    const r = runBatch(env, [...BASE_ARGS, "--host-config", env.host]);
    assert.equal(r.status, 0, r.stderr + r.stdout);
    const log = readLog(env);
    const firstMeasure = log.findIndex((l) => l.includes("MEASURE-START"));
    const preflights = log.filter((l, i) => i < firstMeasure && l.includes("--preflight-only"));
    assert.equal(preflights.filter((l) => l.includes("lab-a")).length, 1);
    assert.equal(preflights.filter((l) => l.includes("lab-b")).length, 1);
  } finally {
    rmSync(env.base, { recursive: true, force: true });
  }
});

test("batch: one blocked lab prevents the entire batch, with an aggregated report", () => {
  const env = makeEnv();
  try {
    rmSync(join(env.confs, "lab-b.conf"));
    const r = runBatch(env, [...BASE_ARGS, "--host-config", env.host]);
    assert.notEqual(r.status, 0);
    assert.match(r.stdout, /lab-a\s+READY/);
    assert.match(r.stdout, /lab-b\s+BLOCKED: missing runner configuration/);
    assert.match(r.stdout, /No measurements started\./);
    assert.equal(readLog(env).length, 0, "the per-lab runner must never be invoked when the batch is blocked");
  } finally {
    rmSync(env.base, { recursive: true, force: true });
  }
});

test("batch: labs execute strictly sequentially and repetitions get unique run archives", () => {
  const env = makeEnv();
  try {
    const r = runBatch(env, [...BASE_ARGS, "--host-config", env.host]);
    assert.equal(r.status, 0, r.stderr);
    const log = readLog(env).filter((l) => l.includes("MEASURE-"));
    // strictly sequential: every START is immediately followed by its own END
    for (let i = 0; i < log.length; i += 2) {
      const lab = log[i].split(" ")[2];
      assert.match(log[i], /MEASURE-START/);
      assert.match(log[i + 1], new RegExp(`MEASURE-END ${lab}`));
    }
    const manifest = latestManifest(env);
    assert.equal(manifest.state, "complete");
    const archives = [];
    for (const entry of Object.values(manifest.labs)) {
      for (const run of entry.runs) {
        assert.equal(run.status, "collected");
        archives.push(run.archive);
      }
    }
    assert.equal(archives.length, 4); // 2 labs × 2 repetitions
    assert.equal(new Set(archives).size, 4, "every repetition must have its own run archive/id");
    // per-lab artifacts stay separated per repetition directory
    const batchDir = join(env.batches, readdirSync(env.batches).sort().pop());
    for (const rep of ["run-1", "run-2"]) {
      assert.ok(existsSync(join(batchDir, rep, "lab-a")) || readdirSync(join(batchDir, rep)).some((f) => f.startsWith("lab-a")));
      assert.ok(readdirSync(join(batchDir, rep)).some((f) => f.startsWith("lab-b")));
    }
  } finally {
    rmSync(env.base, { recursive: true, force: true });
  }
});

test("batch: rejected runs do not count toward repetitions and the batch is labeled partial", () => {
  const env = makeEnv();
  try {
    const r = runBatch(env, [...BASE_ARGS, "--host-config", env.host], { STUB_RUN_EXIT_lab_b_2: "1" });
    assert.notEqual(r.status, 0); // partial batches exit non-zero
    const manifest = latestManifest(env);
    assert.equal(manifest.state, "partial", "a partial batch must never be described as complete");
    const labB = manifest.labs["lab-b"].runs;
    assert.equal(labB.filter((x) => x.status === "collected").length, 1);
    assert.equal(labB.filter((x) => x.status === "rejected-or-failed").length, 1);
    // failed run artifacts are quarantined, not mixed into run dirs
    const batchDir = join(env.batches, readdirSync(env.batches).sort().pop());
    assert.ok(existsSync(join(batchDir, "failed-runs", "lab-b-run-2")));
  } finally {
    rmSync(env.base, { recursive: true, force: true });
  }
});

test("batch: host instability blocks execution instead of measuring under load", () => {
  const env = makeEnv();
  try {
    // a stubbed pgrep that always reports active benchmark processes
    writeFileSync(join(env.bin, "pgrep"), "#!/bin/sh\necho 3\nexit 0\n");
    chmodSync(join(env.bin, "pgrep"), 0o755);
    const r = runBatch(env, [...BASE_ARGS, "--host-config", env.host]);
    assert.notEqual(r.status, 0);
    const manifest = latestManifest(env);
    assert.equal(manifest.state, "partial");
    assert.match(JSON.stringify(manifest), /blocked-unstable-host/);
    assert.equal(readLog(env).filter((l) => l.includes("MEASURE-START")).length, 0);
  } finally {
    rmSync(env.base, { recursive: true, force: true });
  }
});

test("batch: CPU mappings are validated against the lab's requirements", () => {
  const env = makeEnv();
  try {
    let cfg = readFileSync(env.host, "utf8");
    cfg = cfg.replace("lab-b: pair", "lab-b: single");
    writeFileSync(env.host, cfg);
    const r = runBatch(env, [...BASE_ARGS, "--host-config", env.host]);
    assert.notEqual(r.status, 0);
    assert.match(r.stdout, /lab-b\s+BLOCKED: cardinality error: cpu_set 'single' has 1 CPU\(s\), lab requires exactly 2/);
  } finally {
    rmSync(env.base, { recursive: true, force: true });
  }
});

test("batch: an empty cpu_set blocks with instructions to fill it from lscpu", () => {
  const env = makeEnv();
  try {
    let cfg = readFileSync(env.host, "utf8");
    cfg = cfg.replace('pair: "0,1"', 'pair: ""');
    writeFileSync(env.host, cfg);
    const r = runBatch(env, [...BASE_ARGS, "--host-config", env.host]);
    assert.notEqual(r.status, 0);
    assert.match(r.stdout, /cpu_set 'pair' is empty — fill it from lscpu/);
  } finally {
    rmSync(env.base, { recursive: true, force: true });
  }
});

test("batch: whole-script sudo is rejected and no built command uses sudo", () => {
  const env = makeEnv();
  try {
    writeFileSync(join(env.bin, "id"), "#!/bin/sh\necho 0\n");
    chmodSync(join(env.bin, "id"), 0o755);
    const r = runBatch(env, [...BASE_ARGS, "--host-config", env.host]);
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /do not run this script with sudo\/as root/);
    assert.equal(readLog(env).length, 0);

    // Java/Maven/Cargo/JMH/Criterion never run under sudo: every command the
    // evidence library constructs is sudo-free.
    for (const fn of ["build_jmh_evidence_command shared /o", "build_perf_stat_command shared /o 1", "build_c2c_record_command shared /o"]) {
      const out = execFileSync("bash", ["-c", `source '${LIB}'; ${fn}`], {
        env: { ...process.env, EV_CPUS: "0,1", EV_JAR: "j", EV_SELECTOR: "s", EV_JVM_ARGS: "-Xmx1g", EV_JMH_EXTRA: "", EV_THREADS: "2", EV_FORKS: "1", EV_WI: "1", EV_W: "1s", EV_I: "1", EV_R: "1s", EV_PERF_EVENTS: "cycles" },
        encoding: "utf8",
      });
      assert.ok(!out.includes("sudo"), `built command must not use sudo: ${out}`);
    }
    const runnerSrc = readFileSync(join(ROOT, "scripts/performance-lab/run-linux-evidence.sh"), "utf8");
    assert.match(runnerSrc, /do not run this script with sudo\/as root/);
  } finally {
    rmSync(env.base, { recursive: true, force: true });
  }
});

test("batch: --preflight-only validates everything and starts no measurement", () => {
  const env = makeEnv();
  try {
    const r = runBatch(env, [...BASE_ARGS, "--host-config", env.host, "--preflight-only"]);
    assert.equal(r.status, 0, r.stderr);
    assert.match(r.stdout, /No measurements started \(--preflight-only\)\./);
    assert.equal(readLog(env).filter((l) => l.includes("MEASURE-START")).length, 0);
    assert.ok(readLog(env).every((l) => l.includes("--preflight-only")));
  } finally {
    rmSync(env.base, { recursive: true, force: true });
  }
});

test("batch: --dry-run renders the full plan (with cooldowns) and produces no artifacts", () => {
  const env = makeEnv();
  try {
    const r = runBatch(env, [...BASE_ARGS, "--host-config", env.host, "--dry-run"]);
    assert.equal(r.status, 0, r.stderr);
    assert.match(r.stdout, /\[plan\] cooldown 0s \+ stability wait/);
    assert.match(r.stdout, /\[plan\] .*stub-runner\.sh lab-a --profile publication --cpus 0,1/);
    assert.match(r.stdout, /run-2/);
    assert.equal(existsSync(env.batches), false, "dry-run must create no batch artifacts");
    assert.equal(readLog(env).length, 0, "dry-run must not invoke the per-lab runner");
  } finally {
    rmSync(env.base, { recursive: true, force: true });
  }
});

test("batch: publication batches require >= 2 repetitions unless explicitly diagnostic", () => {
  const env = makeEnv();
  try {
    const r = runBatch(env, ["--profile", "publication", "--repetitions", "1", "--host-config", env.host, "--dry-run"]);
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /require --repetitions >= 2/);
    const diag = runBatch(env, ["--profile", "publication", "--repetitions", "1", "--diagnostic", "--host-config", env.host, "--dry-run"]);
    assert.equal(diag.status, 0, diag.stderr);
  } finally {
    rmSync(env.base, { recursive: true, force: true });
  }
});

// --- batch verification ----------------------------------------------------------

function makeMiniLabArchive(dir, name) {
  const run = join(dir, name);
  mkdirSync(run, { recursive: true });
  for (const f of ["environment.json", "topology.txt", "capabilities.json", "toolchain.json", "benchmark-profile.json", "correctness.json"]) {
    writeFileSync(join(run, f), "{}");
  }
  writeFileSync(join(run, "evidence-manifest.json"), JSON.stringify({
    labId: "lab-a", runId: name, sourceCommit: "abc123def456", dirtyTree: false, scenario: "same-socket",
    environment: "environment.json", topology: "topology.txt", capabilities: "capabilities.json",
    toolchain: "toolchain.json", benchmarkProfile: "benchmark-profile.json", correctness: "correctness.json",
    variants: {},
  }));
  execFileSync("bash", ["-c", `source '${LIB}'; write_sha256sums '${run}'`]);
  const archive = join(dir, `lab-a-${name}-linux-evidence.tar.gz`);
  execSync(`tar -czf '${archive}' -C '${dir}' '${name}'`);
  return archive;
}

function makeMiniBatch(stateOverride = "complete") {
  const base = mkdtempSync(join(tmpdir(), "plab-vbatch-"));
  const batchId = "batch-test";
  const batchDir = join(base, batchId);
  mkdirSync(join(batchDir, "run-1"), { recursive: true });
  const archive = makeMiniLabArchive(join(batchDir, "run-1"), "linux-r1");
  const hash = execSync(`shasum -a 256 '${archive}' 2>/dev/null || sha256sum '${archive}'`, { encoding: "utf8" }).split(/\s+/)[0];
  writeFileSync(join(batchDir, "batch-manifest.json"), JSON.stringify({
    batchId, hostName: "test", profile: "publication", repetitions: 1,
    sourceCommit: "abc", dirtyTree: false, startedAt: "x", completedAt: "y",
    executionOrder: ["lab-a"],
    labs: { "lab-a": { cpus: "0,1", preflight: "READY", runs: [{ repetition: 1, status: "collected", archive: `run-1/${archive.split("/").pop()}`, sha256: hash }] } },
    state: stateOverride,
  }, null, 2));
  execFileSync("bash", ["-c", `source '${LIB}'; write_sha256sums '${batchDir}'`]);
  const batchArchive = join(batchDir, `performance-lab-${batchId}.tar.gz`);
  execSync(`tar -czf '${batchArchive}' -C '${base}' '${batchId}'`);
  return { base, batchDir, batchArchive };
}

function runVerifyBatch(archive) {
  try {
    const stdout = execFileSync("bash", [VERIFY_BATCH, archive], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
    return { status: 0, stdout, stderr: "" };
  } catch (err) {
    return { status: err.status ?? 1, stdout: err.stdout?.toString() ?? "", stderr: err.stderr?.toString() ?? "" };
  }
}

test("verify-batch: an intact mini batch passes", () => {
  const { base, batchArchive } = makeMiniBatch();
  try {
    const r = runVerifyBatch(batchArchive);
    assert.equal(r.status, 0, r.stderr + r.stdout);
  } finally {
    rmSync(base, { recursive: true, force: true });
  }
});

test("verify-batch: a tampered per-lab archive is detected", () => {
  const { base, batchDir, batchArchive } = makeMiniBatch();
  try {
    // tamper AFTER the manifest hash was recorded, and rebuild the outer
    // archive so only the inner per-lab hash mismatches
    const inner = readdirSync(join(batchDir, "run-1")).find((f) => f.endsWith(".tar.gz"));
    writeFileSync(join(batchDir, "run-1", inner), "tampered-bytes");
    execFileSync("bash", ["-c", `source '${LIB}'; write_sha256sums '${batchDir}'`]);
    execSync(`tar -czf '${batchArchive}' -C '${base}' 'batch-test'`);
    const r = runVerifyBatch(batchArchive);
    assert.notEqual(r.status, 0);
    assert.match(r.stdout + r.stderr, /hash mismatch|evidence verification failed/);
  } finally {
    rmSync(base, { recursive: true, force: true });
  }
});

test("verify-batch: a missing per-lab archive and an over-claimed complete state are detected", () => {
  const { base, batchDir, batchArchive } = makeMiniBatch();
  try {
    const inner = readdirSync(join(batchDir, "run-1")).find((f) => f.endsWith(".tar.gz"));
    rmSync(join(batchDir, "run-1", inner));
    execFileSync("bash", ["-c", `source '${LIB}'; write_sha256sums '${batchDir}'`]);
    execSync(`tar -czf '${batchArchive}' -C '${base}' 'batch-test'`);
    const r = runVerifyBatch(batchArchive);
    assert.notEqual(r.status, 0);
    assert.match(r.stdout + r.stderr, /missing/);
  } finally {
    rmSync(base, { recursive: true, force: true });
  }
});

test("verify-batch: rejected runs are never counted toward the repetition requirement", () => {
  const { base, batchDir, batchArchive } = makeMiniBatch();
  try {
    const manifest = JSON.parse(readFileSync(join(batchDir, "batch-manifest.json"), "utf8"));
    manifest.repetitions = 2;
    manifest.labs["lab-a"].runs.push({ repetition: 2, status: "rejected-or-failed" });
    manifest.state = "complete"; // over-claimed: only 1 collected of 2 required
    writeFileSync(join(batchDir, "batch-manifest.json"), JSON.stringify(manifest, null, 2));
    execFileSync("bash", ["-c", `source '${LIB}'; write_sha256sums '${batchDir}'`]);
    execSync(`tar -czf '${batchArchive}' -C '${base}' 'batch-test'`);
    const r = runVerifyBatch(batchArchive);
    assert.notEqual(r.status, 0);
    assert.match(r.stdout + r.stderr, /only 1\/2 collected repetitions/);
  } finally {
    rmSync(base, { recursive: true, force: true });
  }
});

// --- import handoff invariants -----------------------------------------------------

test("import-batch: never derives reviewed/verified and never touches public content or capability levels", () => {
  const src = readFileSync(join(ROOT, "scripts/performance-lab/import-benchmark-batch.sh"), "utf8");
  assert.match(src, /at most measured\/reproduced/);
  assert.ok(!src.includes("capability-registry"), "batch import must never promote importer capabilities");
  assert.ok(!/lab\/[a-z-]+\/index\.html/.test(src), "batch import must never touch public pages");
  const perLab = readFileSync(join(ROOT, "scripts/performance-lab/import-linux-evidence.mjs"), "utf8");
  assert.match(perLab, /reproduction: \{ required: 1, completed: 0 \}/, "fresh imports start below reproduced; reviewer fields are never auto-set");
});

// --- host-stability checker (real-5810 batch-20260716T101409Z regression) ---------
// The first real batch classified all 16 runs blocked-unstable-host on a
// host whose load was 1.52/14 cores = 0.109 < 0.20 — because
// `pgrep -fc ... || echo 0` double-printed "0\n0" (Linux pgrep prints 0 AND
// exits 1), corrupting both the JSON and the stability compare.

function libWithStubs(script, stubs, extraEnv = {}) {
  const bin = mkdtempSync(join(tmpdir(), "plab-stub-"));
  try {
    for (const [name, body] of Object.entries(stubs)) {
      writeFileSync(join(bin, name), `#!/bin/sh\n${body}\n`);
      chmodSync(join(bin, name), 0o755);
    }
    try {
      const stdout = execFileSync("bash", ["-c", `source '${LIB}'; ${script}`], {
        env: { ...process.env, PATH: `${bin}:${process.env.PATH}`, ...extraEnv },
        encoding: "utf8", stdio: ["ignore", "pipe", "pipe"],
      });
      return { status: 0, stdout, stderr: "" };
    } catch (err) {
      return { status: err.status ?? 1, stdout: err.stdout?.toString() ?? "", stderr: err.stderr?.toString() ?? "" };
    }
  } finally {
    rmSync(bin, { recursive: true, force: true });
  }
}

test("stability: pgrep printing 0 with exit 1 yields exactly one numeric zero, never 0\\n0", () => {
  const r = libWithStubs("count_benchmark_processes", { pgrep: `echo 0\nexit 1` });
  assert.equal(r.status, 0, r.stderr);
  assert.equal(r.stdout, "0\n", "exactly one non-negative integer + one newline");
});

test("stability: empty pgrep output (macOS semantics) also yields exactly one zero", () => {
  const r = libWithStubs("count_benchmark_processes", { pgrep: `exit 1` });
  assert.equal(r.status, 0, r.stderr);
  assert.equal(r.stdout, "0\n");
});

test("stability: multiple process categories are summed numerically, never concatenated", () => {
  const r = libWithStubs("count_benchmark_processes", {
    pgrep: `case "$*" in
  *run-linux*) echo 1; exit 0 ;;
  *benchmarks*) echo 2; exit 0 ;;
  *evidence*) echo 3; exit 0 ;;
esac
echo 0; exit 1`,
  });
  assert.equal(r.status, 0, r.stderr);
  assert.equal(r.stdout, "6\n", "2+3+1 summed, not concatenated");
});

test("stability: non-numeric or multiline counter output is rejected as an infrastructure error", () => {
  for (const body of [`printf '0\\n0\\n'; exit 0`, `echo garbage; exit 0`]) {
    const r = libWithStubs("count_benchmark_processes", { pgrep: body });
    assert.notEqual(r.status, 0, `stub body ${body} must be rejected`);
    assert.match(r.stderr, /invalid output/);
  }
});

test("stability: a 14-core host at load 1.52 (0.109/core) passes the 0.20 threshold; 0.00 passes; overload fails with explicit reasons", () => {
  const load = mkdtempSync(join(tmpdir(), "plab-load-"));
  try {
    writeFileSync(join(load, "l152"), "1.52 0.43 0.15 1/512 40941\n");
    writeFileSync(join(load, "l000"), "0.00 0.00 0.00 1/512 40941\n");
    writeFileSync(join(load, "l650"), "6.50 5.90 5.10 8/900 23456\n");
    const ok = libWithStubs(`evaluate_stability_sample '${join(load, "l152")}' 14 20 0`, {});
    assert.equal(ok.status, 0, ok.stdout + ok.stderr);
    const sample = JSON.parse(ok.stdout);
    assert.equal(sample.stable, true);
    assert.equal(sample.load1, 1.52);
    assert.equal(sample.logicalCpus, 14);
    assert.ok(Math.abs(sample.loadPerCore - 0.1086) < 0.001);
    assert.equal(sample.maxLoadPerCore, 0.2);
    assert.equal(sample.benchmarkProcesses, 0);

    const idle = libWithStubs(`evaluate_stability_sample '${join(load, "l000")}' 14 20 0`, {});
    assert.equal(idle.status, 0);
    assert.equal(JSON.parse(idle.stdout).stable, true);

    const busy = libWithStubs(`evaluate_stability_sample '${join(load, "l650")}' 14 20 5`, {});
    assert.notEqual(busy.status, 0);
    const rejected = JSON.parse(busy.stdout);
    assert.equal(rejected.stable, false);
    assert.equal(rejected.reasons.length, 2, "every failing condition listed");
    assert.match(rejected.reasons[0], /loadPerCore 0\.4643 > max 0\.20/);
    assert.match(rejected.reasons[1], /benchmarkProcesses 5 != 0/);
  } finally {
    rmSync(load, { recursive: true, force: true });
  }
});

test("stability: generated before/after environment files parse as JSON with integer benchmarkProcesses", () => {
  const env = makeEnv();
  try {
    const r = runBatch(env, [...BASE_ARGS, "--host-config", env.host]);
    assert.equal(r.status, 0, r.stderr);
    const batchDir = join(env.batches, readdirSync(env.batches).sort().pop());
    for (const f of ["before-batch.json", "after-batch.json"]) {
      const parsed = JSON.parse(readFileSync(join(batchDir, "host-environment", f), "utf8"));
      assert.equal(typeof parsed.benchmarkProcesses, "number", `${f}: benchmarkProcesses must be a JSON integer`);
      assert.equal(parsed.benchmarkProcesses, 0);
    }
    // consecutive-sample evidence: the samples artifact exists with >= 3
    // stable samples for the first gate, and measurement proceeded
    // immediately (no 600s wait)
    const samples = readFileSync(join(batchDir, "host-stability-samples.jsonl"), "utf8").trim().split("\n").map((l) => JSON.parse(l));
    assert.ok(samples.length >= 3);
    assert.ok(samples.every((s) => s.stable === true));
    const manifest = latestManifest(env);
    assert.equal(manifest.state, "complete");
    assert.equal(manifest.publicationEligible, true);
    assert.equal(manifest.stabilityPolicy.consecutiveSamples, 3);
  } finally {
    rmSync(env.base, { recursive: true, force: true });
  }
});

test("stability: malformed metrics abort the ENTIRE batch as infrastructure failure, not host instability", () => {
  const env = makeEnv();
  try {
    // pgrep emits the historical double-zero garbage with exit 0 —
    // an invalid metric, not a busy host
    writeFileSync(join(env.bin, "pgrep"), `#!/bin/sh\nprintf '0\\n0\\n'\nexit 0\n`);
    chmodSync(join(env.bin, "pgrep"), 0o755);
    const r = runBatch(env, [...BASE_ARGS, "--host-config", env.host]);
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /failed-environment-capture|invalid-stability-metric/);
    assert.doesNotMatch(r.stderr, /did not become stable/);
    // aborted at first occurrence: no measurement ran at all
    assert.equal(readLog(env).filter((l) => l.includes("MEASURE-START")).length, 0);
    const manifest = latestManifest(env);
    assert.equal(manifest.state, "failed-infrastructure");
  } finally {
    rmSync(env.base, { recursive: true, force: true });
  }
});

test("stability: --stability-check-only validates and reports without any benchmark, build or archive", () => {
  const env = makeEnv();
  try {
    const r = runBatch(env, ["--profile", "publication", "--repetitions", "2", "--host-config", env.host, "--stability-check-only"]);
    assert.equal(r.status, 0, r.stderr + r.stdout);
    assert.match(r.stdout, /thresholds: maxLoadPerCore=0\.20 consecutiveSamples=3/);
    assert.match(r.stdout, /Host stable \(3 consecutive good samples\)\./);
    assert.match(r.stdout, /"benchmarkProcesses": 0/);
    assert.equal(readLog(env).length, 0, "no per-lab runner invocation");
    assert.equal(existsSync(env.batches), false, "no batch artifacts, no archive");
  } finally {
    rmSync(env.base, { recursive: true, force: true });
  }
});

test("stability: a dirty tree blocks the publication profile before measurement, with paths listed; smoke stays publication-ineligible", () => {
  const env = makeEnv();
  try {
    writeFileSync(join(env.bin, "git"), `#!/bin/sh
case "$*" in
  *status*--porcelain*) echo " M content/labs/false-sharing/theory.md"; echo "?? scratch.txt"; exit 0 ;;
  *rev-parse*) echo "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"; exit 0 ;;
  *log*) echo "{}"; exit 0 ;;
esac
exit 0
`);
    chmodSync(join(env.bin, "git"), 0o755);
    const pub = runBatch(env, [...BASE_ARGS, "--host-config", env.host]);
    assert.notEqual(pub.status, 0);
    assert.match(pub.stderr, /dirty working tree/);
    assert.match(pub.stderr, /content\/labs\/false-sharing\/theory\.md/);
    assert.match(pub.stderr, /scratch\.txt/);
    assert.equal(readLog(env).filter((l) => l.includes("MEASURE-START")).length, 0);

    const smoke = runBatch(env, ["--profile", "smoke", "--repetitions", "1", "--diagnostic", "--host-config", env.host]);
    assert.equal(smoke.status, 0, smoke.stderr);
    const manifest = latestManifest(env);
    assert.equal(manifest.publicationEligible, false, "a dirty smoke batch is permanently publication-ineligible");
  } finally {
    rmSync(env.base, { recursive: true, force: true });
  }
});

test("stability: the real malformed environment fixture is rejected by JSON validation", () => {
  const fixture = join(ROOT, "scripts", "performance-lab", "__fixtures__", "malformed-environment.json");
  const r = libWithStubs(`validate_json_file '${fixture}'`, {});
  assert.notEqual(r.status, 0, "the double-zero environment document must fail structural validation");
});

// --- source-tree integrity (real-5810 dirty-tree regression, 2026-07-17) ----------
// The publication batch aborted because live preflight's own Maven build
// modified TRACKED target/ files, and the clean-tree gate ran after
// preflight. Build outputs are now ignored repository-wide, the gate runs
// BEFORE any build, and a tracked change during preflight is its own
// failure class.

test("source: no generated build output (target/) is tracked by git", () => {
  const tracked = execSync("git ls-files | grep -E '(^|/)(target)/' || true", { cwd: ROOT, encoding: "utf8" }).trim();
  assert.equal(tracked, "", `tracked build outputs found:\n${tracked}`);
  const ignore = readFileSync(join(ROOT, ".gitignore"), "utf8");
  assert.match(ignore, /^\*\*\/target\/$/m, ".gitignore must ignore Maven/Cargo target dirs repository-wide");
});

test("source: files under an ignored target/ never appear as tree drift (real git semantics)", () => {
  const probe = join(ROOT, "content/labs/jit-pipeline/code/java/target", `probe-${Date.now()}.tmp`);
  mkdirSync(join(ROOT, "content/labs/jit-pipeline/code/java/target"), { recursive: true });
  try {
    writeFileSync(probe, "generated");
    const porcelain = execSync("git status --porcelain", { cwd: ROOT, encoding: "utf8" });
    assert.ok(!porcelain.includes("probe-"), "an ignored generated file must not show in git status --porcelain");
  } finally {
    rmSync(probe, { force: true });
  }
});

test("source: an initially dirty tree blocks the publication batch before ANY build, test or preflight run", () => {
  const env = makeEnv();
  try {
    writeFileSync(join(env.bin, "git"), `#!/bin/sh
case "$*" in
  *status*--porcelain*) echo " M content/labs/mesi/theory.md"; exit 0 ;;
  *rev-parse*) echo "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"; exit 0 ;;
esac
exit 0
`);
    chmodSync(join(env.bin, "git"), 0o755);
    const r = runBatch(env, [...BASE_ARGS, "--host-config", env.host]);
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /checked BEFORE any build\/test/);
    assert.match(r.stderr, /content\/labs\/mesi\/theory\.md/);
    assert.equal(readLog(env).length, 0, "the per-lab runner (and therefore Maven/Cargo) must never have been invoked");
  } finally {
    rmSync(env.base, { recursive: true, force: true });
  }
});

test("source: a tracked file mutating DURING live preflight is failed-preflight-source-mutation, and no measurement starts", () => {
  const env = makeEnv();
  try {
    // stateful git stub: clean on the first porcelain call (the pre-build
    // gate), dirty afterwards (as if preflight modified a tracked file)
    writeFileSync(join(env.bin, "git"), `#!/bin/sh
case "$*" in
  *status*--porcelain*)
    n=$(cat "${env.base}/git-calls" 2>/dev/null || echo 0); n=$((n+1)); echo "$n" > "${env.base}/git-calls"
    if [ "$n" -gt 1 ]; then echo " M content/labs/lab-a/theory.md"; fi
    exit 0 ;;
  *rev-parse*) echo "cccccccccccccccccccccccccccccccccccccccc"; exit 0 ;;
  *log*) echo "{}"; exit 0 ;;
esac
exit 0
`);
    chmodSync(join(env.bin, "git"), 0o755);
    const r = runBatch(env, [...BASE_ARGS, "--host-config", env.host]);
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /failed-preflight-source-mutation/);
    assert.match(r.stderr, /content\/labs\/lab-a\/theory\.md/, "the exact mutated path must be reported");
    assert.doesNotMatch(r.stderr, /dirty working tree — publication/, "never classified as user dirty-tree state");
    assert.equal(readLog(env).filter((l) => l.includes("MEASURE-START")).length, 0);
    const manifest = latestManifest(env);
    assert.equal(manifest.state, "failed-preflight-source-mutation");
  } finally {
    rmSync(env.base, { recursive: true, force: true });
  }
});

test("source: a clean tree proceeds through live preflight and records the exact commit in the manifest", () => {
  const env = makeEnv();
  try {
    const r = runBatch(env, [...BASE_ARGS, "--host-config", env.host]);
    assert.equal(r.status, 0, r.stderr);
    const manifest = latestManifest(env);
    assert.equal(manifest.sourceCommit, "a".repeat(40));
    assert.equal(manifest.dirtyTree, false);
    assert.equal(manifest.sourceStateBeforePreflight, "clean");
  } finally {
    rmSync(env.base, { recursive: true, force: true });
  }
});

// --- reference-tier discovery against the REAL repository -------------------------

const LABS_DIR_REAL = join(ROOT, "scripts", "performance-lab", "labs");

// Runs a snippet with evidence-lib sourced; returns { status, stdout, stderr }.
function libCall(script) {
  try {
    const stdout = execFileSync("bash", ["-c", `source '${LIB}'; ${script}`], {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    });
    return { status: 0, stdout, stderr: "" };
  } catch (err) {
    return { status: err.status ?? 1, stdout: err.stdout?.toString() ?? "", stderr: err.stderr?.toString() ?? "" };
  }
}

const REFERENCE_LABS = [
  "cache-hierarchy", "false-sharing", "mesi", "memory-ordering",
  "cas-contention", "spsc-ring-buffer", "thread-per-core", "jit-pipeline",
];

test("reference tier: the batch runner discovers all 8 reference labs and none is blocked or silently excluded", () => {
  // Real content root and real confs; only the host config is a filled
  // test fixture (the shipped template deliberately has empty cpu_sets).
  const base = mkdtempSync(join(tmpdir(), "plab-ref-"));
  try {
    writeFileSync(join(base, "host.yaml"), `schema_version: 1
host_name: local-test
max_load_per_core_x100: 99
stability_timeout_seconds: 30

cooldown_seconds:
  short: 0
  standard: 0
  long: 0

cpu_sets:
  core_single: "0"
  core_pair: "0,1"
  core_quad: "0,1,2,3"
  core_octet: "0,1,2,3,4,5,6,7"

labs:
  false-sharing: core_pair
  spsc-ring-buffer: core_pair
  cas-contention: core_octet
  cache-hierarchy: core_single
  mesi: core_pair
  memory-ordering: core_pair
  thread-per-core: core_quad
  jit-pipeline: core_single
`);
    const r = (() => {
      try {
        const stdout = execFileSync("bash", [BATCH,
          "--profile", "publication", "--repetitions", "2",
          "--host-config", join(base, "host.yaml"), "--dry-run",
        ], {
          env: { ...process.env, PLAB_BATCH_ROOT: join(base, "batches") },
          encoding: "utf8", cwd: ROOT, stdio: ["ignore", "pipe", "pipe"],
        });
        return { status: 0, stdout, stderr: "" };
      } catch (err) {
        return { status: err.status ?? 1, stdout: err.stdout?.toString() ?? "", stderr: err.stderr?.toString() ?? "" };
      }
    })();
    assert.equal(r.status, 0, r.stdout + r.stderr);
    for (const lab of REFERENCE_LABS) {
      assert.match(r.stdout, new RegExp(`${lab}\\s+READY`), `${lab} must be READY in the reference batch preflight`);
      assert.match(r.stdout, new RegExp(`\\[plan\\] .*run-linux-evidence\\.sh ${lab} `), `${lab} must appear in the planned sequence`);
    }
    assert.doesNotMatch(r.stdout, /BLOCKED/, "no reference lab may be blocked");
    assert.equal(existsSync(join(base, "batches")), false, "dry-run creates no artifacts");
  } finally {
    rmSync(base, { recursive: true, force: true });
  }
});

test("reference tier: every lab's evidence configuration is structurally complete", () => {
  for (const lab of REFERENCE_LABS) {
    const conf = join(ROOT, "scripts", "performance-lab", "labs", `${lab}.conf`);
    assert.ok(existsSync(conf), `${lab}.conf missing`);
    const r = libCall(`CPU_LIST=(0 1 2 3 4 5 6 7); source '${conf}';
      [ "\${LAB_BENCHMARK_READY:-0}" = 1 ] || { echo not-ready; exit 3; }
      declare -f lab_variants lab_jmh_args lab_threads lab_cpu_count lab_worker_props lab_rust_evidence_cmd >/dev/null || { echo missing-fn; exit 3; }
      for v in $(lab_variants); do
        lab_jmh_args "$v" >/dev/null; lab_threads "$v" >/dev/null; lab_cpu_count "$v" >/dev/null; lab_worker_props "$v" >/dev/null
        if declare -f lab_variant_kind >/dev/null && [ "$(lab_variant_kind "$v")" = "aux" ]; then
          [ -n "$(lab_aux_evidence_cmd "$v")" ] || { echo "aux-cmd-empty:$v"; exit 3; }
        fi
      done
      [ -d '${ROOT}/'"$LAB_JAVA_DIR" ] || { echo missing-java; exit 3; }
      find '${ROOT}/'"$LAB_JAVA_DIR"/src/test -name '*.java' | grep -q . || { echo missing-tests; exit 3; }
      echo OK "$(lab_variants | wc -w | tr -d ' ')"`);
    assert.equal(r.status, 0, `${lab}: ${r.stdout} ${r.stderr}`);
    assert.match(r.stdout, /OK [1-9]/, `${lab} must declare at least one variant`);
  }
});

test("reference tier: methodology-separation invariants hold in the configs", () => {
  // litmus experiments are aux (never JMH latency), warm-up/deopt
  // trajectories are aux (never merged with steady state), the SPSC
  // publication harness is the persistent-worker bin, and MESI requires
  // coherence (c2c) evidence.
  const mo = libCall(`CPU_LIST=(0 1); source '${join(LABS_DIR_REAL, "memory-ordering.conf")}'; lab_variant_kind litmus-sb-opaque; lab_variant_kind cost-volatile`);
  assert.equal(mo.stdout.trim().split("\n").join(","), "aux,jmh");
  const jit = libCall(`CPU_LIST=(0); source '${join(LABS_DIR_REAL, "jit-pipeline.conf")}'; lab_variant_kind warmup-trajectory; lab_variant_kind steady-mono`);
  assert.equal(jit.stdout.trim().split("\n").join(","), "aux,jmh");
  const spsc = libCall(`CPU_LIST=(0 1); source '${join(LABS_DIR_REAL, "spsc-ring-buffer.conf")}'; lab_rust_evidence_cmd cached-b1-c1024`);
  assert.match(spsc.stdout, /--bin spsc_evidence/);
  assert.doesNotMatch(spsc.stdout, /cargo bench/);
  const mesi = libCall(`source '${join(LABS_DIR_REAL, "mesi.conf")}'; echo "$LAB_C2C_REQUIRED"`);
  assert.equal(mesi.stdout.trim(), "1");
});

test("host config: the shipped Precision 5810 template conforms to its schema shape", () => {
  const yaml = readFileSync(join(ROOT, "config/benchmark-hosts/precision-5810.yaml"), "utf8");
  assert.match(yaml, /^schema_version: 1$/m);
  for (const key of ["host_name", "max_load_per_core_x100", "stability_timeout_seconds"]) {
    assert.match(yaml, new RegExp(`^${key}: `, "m"));
  }
  for (const set of ["core_single", "core_pair", "core_quad", "core_octet"]) {
    const m = yaml.match(new RegExp(`^  ${set}: (.*)$`, "m"));
    assert.ok(m, `${set} must be present`);
    // accepted forms: "" (unfilled), "2,3" quoted, 2,3 bare, [2, 3] flow
    // list — the batch parser canonicalizes all of them; values come from
    // live lscpu, never guessed from the host model.
    const normalized = m[1].replace(/["\[\] ]/g, "");
    assert.match(normalized, /^$|^[0-9]+(,[0-9]+)*$/,
      `${set} value "${m[1]}" must normalize to an empty string or a digits-only CSV`);
  }
  for (const lab of ["false-sharing", "spsc-ring-buffer", "cas-contention"]) {
    assert.match(yaml, new RegExp(`^  ${lab}: `, "m"));
  }
});

// --- failed-benchmark-timeout (batch-20260717T150131Z hang regression) --------
// The per-lab runner exits 3 when a benchmark invocation exceeded its hard
// wall-clock budget. The batch must abort on the spot: a host that just
// carried a hung 100%-CPU JVM for hours must not keep measuring, and a hang
// needs human attention — it is never skipped like an ordinary rejection.

test("batch: a runner exit of 3 (failed-benchmark-timeout) aborts the entire batch immediately", () => {
  const env = makeEnv();
  try {
    const r = runBatch(env, [...BASE_ARGS, "--host-config", env.host], { STUB_RUN_EXIT_lab_a_1: "3" });
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /failed-benchmark-timeout on lab-a/);
    assert.match(r.stderr, /aborting the entire batch/);
    assert.match(r.stderr, /diagnostics/);
    const manifest = latestManifest(env);
    assert.equal(manifest.state, "failed-benchmark-timeout");
    const labA = manifest.labs["lab-a"].runs;
    assert.equal(labA.length, 1);
    assert.equal(labA[0].status, "failed-benchmark-timeout");
    // nothing after the timeout ran: no lab-b measurement, no repetition 2
    const measures = readLog(env).filter((l) => l.includes("MEASURE-START"));
    assert.equal(measures.length, 1, `expected exactly one measurement attempt, got:\n${measures.join("\n")}`);
    // the timed-out run is quarantined as diagnostics, never mixed with evidence
    const batchDir = join(env.batches, readdirSync(env.batches).sort().pop());
    assert.ok(existsSync(join(batchDir, "failed-runs", "lab-a-run-1")));
  } finally {
    rmSync(env.base, { recursive: true, force: true });
  }
});
