import { execFileSync } from "node:child_process";
import os from "node:os";

// Runs a fixed-argv command (never a shell string, so there is no injection
// surface) and returns trimmed stdout, or null if the binary is missing or
// the probe fails. Every probe in this module is read-only and needs no
// elevated privilege — per design.md "Use non-privileged probes by default."
function probe(cmd, args) {
  try {
    return execFileSync(cmd, args, { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim();
  } catch {
    return null;
  }
}

// Best-effort core topology. Returns { performanceCores, efficiencyCores } on
// Apple Silicon (via sysctl hw.perflevelN), { logicalCores } on generic
// Linux/other, or null with no fabricated split when neither is available.
function captureCoreTopology(platform) {
  if (platform === "darwin") {
    const perf0 = probe("sysctl", ["-n", "hw.perflevel0.physicalcpu"]);
    const perf1 = probe("sysctl", ["-n", "hw.perflevel1.physicalcpu"]);
    if (perf0 !== null) {
      return {
        kind: "apple-silicon-perflevels",
        performanceCores: Number(perf0),
        efficiencyCores: perf1 !== null ? Number(perf1) : null,
      };
    }
    return null;
  }
  if (platform === "linux") {
    const lscpu = probe("lscpu", []);
    if (lscpu !== null) {
      return { kind: "lscpu-raw", raw: lscpu };
    }
    return null;
  }
  return null;
}

// Linux-only CPU frequency governor. macOS and Windows expose no equivalent
// non-privileged read, so this is "not-applicable", not "unavailable" — a
// capability distinction the comparability validator can rely on.
function captureGovernor(platform) {
  if (platform !== "linux") {
    return { status: "not-applicable", reason: `no exposed CPU governor on ${platform}` };
  }
  const governor = probe("cat", ["/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"]);
  if (governor === null) {
    return { status: "unavailable", reason: "scaling_governor read failed (no cpufreq driver, or sandboxed)" };
  }
  return { status: "detected", value: governor };
}

function captureOsVersion(platform) {
  if (platform === "darwin") {
    const productVersion = probe("sw_vers", ["-productVersion"]);
    return productVersion !== null ? `macOS ${productVersion}` : null;
  }
  if (platform === "linux") {
    const prettyName = probe("sh", ["-c", "grep -m1 PRETTY_NAME /etc/os-release 2>/dev/null"]);
    if (prettyName !== null) {
      const match = /PRETTY_NAME="?([^"]+)"?/.exec(prettyName);
      if (match) return match[1];
    }
    return null;
  }
  return null;
}

function captureToolchainVersions() {
  // `java -version` writes to stderr, not stdout, so probe() (which discards
  // stderr) can't see it directly — route through a shell to merge streams.
  const javaVersion = probe("sh", ["-c", "java -version 2>&1"]);
  const mvnVersion = probe("sh", ["-c", "mvn -v 2>&1"]);
  const rustcVersion = probe("rustc", ["--version"]);
  const cargoVersion = probe("cargo", ["--version"]);
  // Both `java -version` and `mvn -v` can print unrelated warnings (JVM
  // native-access notices, restricted-method deprecations) ahead of the
  // actual version line, so pick the line that looks like the real one
  // instead of assuming line 0.
  const javaLine = javaVersion?.split("\n").find((line) => /version/i.test(line)) ?? null;
  const mvnLine = mvnVersion?.split("\n").find((line) => line.startsWith("Apache Maven")) ?? null;
  return {
    java: javaLine,
    maven: mvnLine,
    rustc: rustcVersion,
    cargo: cargoVersion,
  };
}

// Captures host/environment metadata available without unsafe privilege
// escalation, per the "Host metadata" requirement in
// openspec/changes/plab-002-reproducible-benchmark-platform/specs/benchmark-reproducibility/spec.md.
// Every field is either a real, live-probed value or an explicit
// {status: "unavailable"|"not-applicable", reason} — never a guess.
function captureEnvironment() {
  const platform = os.platform();
  const cpus = os.cpus();
  const toolchains = captureToolchainVersions();

  return {
    capturedAt: new Date().toISOString(),
    arch: os.arch(),
    platform,
    osVersion: captureOsVersion(platform) ?? { status: "unavailable", reason: "no platform-specific version probe succeeded" },
    kernelRelease: os.release(),
    cpu: {
      model: cpus[0]?.model ?? null,
      logicalCores: cpus.length,
      nominalSpeedMHz: cpus[0]?.speed ?? null,
    },
    coreTopology: captureCoreTopology(platform) ?? { status: "unavailable", reason: "no topology probe succeeded for this platform" },
    powerManagement: captureGovernor(platform),
    memory: {
      totalBytes: os.totalmem(),
      freeBytes: os.freemem(),
    },
    toolchains,
  };
}

export { captureEnvironment };
