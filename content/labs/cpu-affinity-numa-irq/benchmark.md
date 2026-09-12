# CPU Affinity, NUMA and IRQ Placement — benchmark methodology

<div class="disclosure measured">
  <p class="disclosure-kind">Illustrative development run</p>
  <p>These preliminary values were captured on an Apple M1 Max developer
  workstation under uncontrolled desktop conditions. <strong>This host has
  no Linux <code>sched_setaffinity</code> and no discoverable NUMA
  topology (single-package Apple Silicon SoC)</strong> — every placement
  target other than the unpinned baseline resolves to "topology
  unavailable" here and silently runs unpinned, exactly like the
  baseline. These numbers therefore do NOT demonstrate any pinning or
  NUMA effect; they exist only to confirm the harness runs end-to-end.
  Canonical placement evidence for this laboratory requires the dedicated
  native-Linux benchmark host, where real topology and real
  <code>sched_setaffinity</code> are both available
  (<code>docs/measurement-environments.md</code>).</p>
  <p>Run details: JMH 1.37, OpenJDK 26.0.1 (HotSpot), Apple M1 Max (10
  cores: 8P + 2E), macOS 26.6.2, arm64. Rust: Criterion 0.5.1, same
  machine, reduced sample size (10) for a fast smoke pass.</p>
</div>

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist, but no canonical evidence from the dedicated native-Linux
benchmark host has been imported for this laboratory yet — no verified
placement effect is available, and the development numbers below are not
a substitute. This is the one laboratory in this curriculum where the
"awaiting" state is not merely about statistical rigor: the mechanism
itself (real CPU/NUMA topology) does not exist on the fallback
development host at all.

## Method

Both languages define one benchmark operation per placement target:
resolve a target CPU from the real (or absent) host topology, spawn one
worker thread, best-effort pin it, then run one of three pure checksum
workloads. Because the checksum never depends on placement, any timing
difference the native-Linux run finds can be attributed to placement
alone — this is the semantic-equivalence contract for this lab.

## Illustrative development data (this run — placement unavailable, not a portable claim)

**Java (JMH, `Mode.AverageTime`, µs/op):**

| Benchmark (target / dataset) | Time |
|---|---|
| `unpinnedMemoryScan` | 250.741 µs/op |
| `pinnedIsolatedCoreMemoryScan` | 254.089 µs/op |
| `smtSiblingsSpscHandoff` | 49.622 µs/op |
| `sameNumaNodeUdpIngest` | 281.127 µs/op |
| `remoteNumaNodeUdpIngest` | 280.167 µs/op |

**Rust (Criterion, µs, median of 10 samples):**

| Benchmark (target / dataset) | Time |
|---|---|
| `unpinned_memory_scan` | 146.21 µs |
| `pinned_isolated_core_memory_scan` | 146.31 µs |
| `smt_siblings_spsc_handoff` | 30.581 µs |
| `same_numa_node_udp_ingest` | 200.53 µs |
| `remote_numa_node_udp_ingest` | 200.56 µs |

## What this shows

**`pinnedIsolatedCoreMemoryScan` and `unpinnedMemoryScan` land within
noise of each other in both languages, as do `sameNumaNodeUdpIngest` and
`remoteNumaNodeUdpIngest`** — exactly the expected (non-)result on a host
where pinning silently fails to attempt anything: both members of each
pair run identical unpinned code, so there is no placement effect to
observe. This is the disclosure block's point made numerically: these
pairs are not evidence that pinning or NUMA placement doesn't matter, they
are evidence that this host cannot exercise the mechanism at all.

**The cross-dataset differences (`memoryScan` vs. `spscHandoff` vs.
`udpIngest`) are real but expected and uninteresting for this lab** —
they simply reflect each dataset's own computational cost (200,000-element
scan vs. 5,000-step handoff vs. 2,000×128-byte packet checksum), not a
placement effect, since every number in this table used the same
(non-)placement.

**Neither language's numbers say anything about CPU affinity, NUMA or IRQ
placement on real multi-socket hardware** — that is exactly what the
"awaiting native-Linux measurement" state above means, and why this is
the one laboratory in the curriculum whose informative content is
necessarily concentrated in Limitations and the native-Linux evidence run
rather than in any development-machine number.

## Raw data and reproduction

```sh
# Correctness gate — run before trusting any timing:
node scripts/benchmark-platform/run-correctness-gate.js cpu-affinity-numa-irq

# Smoke run (wiring check only — zero statistical value, and placement is
# unavailable on non-Linux hosts regardless of profile):
cd content/labs/cpu-affinity-numa-irq/code/java && mvn -q -DskipTests package && \
  java -jar target/benchmarks.jar -f 1 -wi 1 -i 2 -r 500ms -w 500ms
cd content/labs/cpu-affinity-numa-irq/code/rust && cargo bench -- --noplot
```

The runnable projects are committed at `code/java/` (Maven + JMH) and
`code/rust/` (Cargo + Criterion) next to this file. Re-run on the
dedicated native-Linux host for the topology probe to actually resolve
real CPU ids — on any other host, every non-baseline target degrades to
the unpinned baseline, by design, not by omission.
