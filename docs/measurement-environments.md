# Measurement environments and evidence states (canonical policy)

The Performance Lab's canonical measurement environment is the dedicated
native-Linux benchmark host (currently a Dell Precision Tower 5810, Intel
Xeon E5-2680 v4, 14 physical cores, 64 GB RAM, Ubuntu Linux, x86_64).
Mutable details — kernel build, microcode, governor, turbo state, JDK
vendor/version/build, logical CPU numbering — are **captured during every
run** (`environment.json`, `topology.txt`, `toolchain.json`), never
hardcoded or assumed. The label "Precision Tower 5810" alone is not
sufficient provenance.

## Environment classes

### 1. Development workstation (`developer-workstation`)

Example: Apple M1 Max, macOS, ordinary desktop load, uncontrolled
scheduler placement, no CPU affinity, no native Linux `perf` evidence.

**Allowed:** implementation; correctness tests; smoke benchmarks;
exploratory measurements; detecting the likely *shape* of an effect.

**Forbidden:** publication-grade absolute results; verified evidence;
regression baselines; Java-versus-Rust performance conclusions;
hardware-counter conclusions; cache-coherence conclusions; final ranking
claims.

Presentation label for its results: **"Illustrative development run"** —
one consistent label repository-wide. Never a bare "Measured". The
restrictions are machine-derived, not badge-deep: such records carry
`evidence.legacy: true` (historic transcriptions → derived maturity
`legacy-unprovenanced`) or `evidence.environment:
"native-uncontrolled"`/`"unknown"`, and `evidence-maturity.js` /
`regression.js` / `comparison-guard.js` derive every restriction from
those fields — a presentation badge cannot override them.

### 2. Dedicated native-Linux benchmark host (`native-linux-host`)

**Required for:** publication measurements; reproduced results; verified
results; regression baselines; Java-versus-Rust comparisons; `perf stat`;
`perf record`; `perf c2c`; affinity- and topology-sensitive conclusions.

Collected exclusively through `scripts/performance-lab/run-linux-evidence.sh`
(docs/linux-evidence-runner.md): explicit physical-core placement,
capability checks, correctness gate, independent JVM forks, full
environment/toolchain capture, hashes, immutable run directories.

### 3. Shared CI, VM, container, emulation (`virtualized`)

Correctness and smoke validation only. **Always publication-ineligible** —
`run-linux-evidence.sh` refuses publication/full profiles in these
environments, with or without `--allow-virtualized` (which permits
smoke/development wiring checks only and records
`publicationEligible: false, environmentKind: "virtualized"`).

## Evidence states (per lab, per result set)

```
planned → implemented → awaiting-native-linux-measurement → measured
        → reproduced → reviewed → verified → published
```

- **planned** — curriculum entry exists; no content/code.
- **implemented** — benchmark and correctness code exist.
- **awaiting-native-linux-measurement** — publication runner/config exist;
  no canonical host evidence has been imported. Rendered literally as
  "Awaiting native-Linux measurement"; no placeholder numbers are shown as
  canonical results, and development-workstation numbers are never
  substituted for the missing evidence.
- **measured** — one valid controlled-host run imported (raw + canonical
  artifacts, hashes, provenance).
- **reproduced** — required independent run count satisfied
  (`evidence.reproduction.completed >= required >= 1`).
- **reviewed** — raw artifacts and methodology passed human review
  (recorded reviewer identity).
- **verified** — every gate passed (provenance, correctness, environment
  `native-controlled`, reproduction, profiling, comparability, review,
  importer capability) — derived by `evidence-maturity.js`, never stored.
- **published** — verified results visible publicly.

A development-workstation run can never advance a lab beyond
`implemented` / `awaiting-native-linux-measurement`. Importing one valid
Linux run advances only to `measured` — reproduction and review remain
required for `verified`.

## Mixed-host comparisons are rejected

Direct Java-versus-Rust or regression comparisons are only valid when both
inputs come from the same environment class **and** compatible environment
manifests. `scripts/benchmark-platform/results/comparison-guard.js`
(`canCompare(a, b)`) rejects, among others:

- Java on M1 Max vs Rust on Precision 5810,
- an old macOS result vs a new Linux result,
- a JDK 26 developer run vs a JDK 21 publication run,
- any legacy-unprovenanced record vs anything.

Development results may appear in a separate historical/exploratory
section, but never in a canonical comparison table.

## Standard handoff for every measured lab

Implementation work delivers: executable benchmark code, correctness
tests, a publication profile, native-Linux runner configuration, the exact
command for the user, the expected artifact layout, and the verify/import
commands. Every implementation completion report includes a
**"Native measurement required"** section with the exact command, e.g.:

```sh
./scripts/performance-lab/run-linux-evidence.sh \
  false-sharing \
  --profile publication-core \
  --cpus <CPU_A>,<CPU_B>
```

(Normal user — never sudo; the one-time privileged perf setup is
`sudo sysctl kernel.perf_event_paranoid=-1`, run separately.)

(Choose `<CPU_A>,<CPU_B>` as two different physical cores, same
socket/NUMA node — the runner validates and refuses anything else;
`--preflight-only` first checks the host without measuring.)

The lab is not described as measured, reproduced, or verified until the
user has executed the workflow and the resulting archive has been imported
and (for verified) reviewed.
