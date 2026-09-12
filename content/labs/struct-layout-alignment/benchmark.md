# Struct layout, alignment and padding — benchmark methodology

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist, but no canonical evidence from the dedicated native-Linux
benchmark host has been imported for this laboratory yet — no verified
performance conclusion is available. Development-machine runs validate
wiring and correctness only and are never published as measured results
(`docs/measurement-environments.md`). This lab's 64-byte cache-line
assumption specifically must be confirmed against the measured host
before any `cacheLineAligned` conclusion is trusted — see Limitations.

## What is deterministic vs what is measured

- **Deterministic** — every sequential-access variant's checksum over a
  given dataset's identical value stream, and every `repr(C)` variant's
  byte size (asserted in both languages' test suites to match exactly):
  storage layout must never change the result
  (`code/fixtures/struct-layout-alignment-fixtures.json`).
- **Measured** — bytes/record, ns/access, cache-line crossings and
  unaligned-load evidence for the sequential datasets; throughput
  (ops/ms) for `producerConsumerCounters`; only from the native-Linux
  evidence runner (sequential datasets) and manual pinned runs
  (`producerConsumerCounters` — see below).

## Operation definitions (the contract)

| Measured artifact | One "operation" | Oracle |
|---|---|---|
| `mixedPrimitiveRecord` / `headerPlusPayload`, `sequentialSum` | one full pass over the declared dataset, summing every record's fields | the variant's checksum matches the fixture-pinned value for the declared dataset |
| `producerConsumerCounters`, `produce`/`consume` | one `volatile`/atomic increment of the thread's own counter | after N increments per thread, both counters equal N exactly, for every layout variant |

`EV_SELECTOR` covers only the sequential-access datasets
(`StructLayoutLinuxEvidenceBenchmark`,
`scripts/performance-lab/labs/struct-layout-alignment.conf`) — the
runner's single-EV_SELECTOR-per-lab design does not fit
`producerConsumerCounters`' two-thread `@Group`/`Scope.Group` throughput
shape in the same automated loop. Its evidence
(`CounterLayoutLinuxEvidenceBenchmark`, five variants) is captured via
manual pinned runs, documented under Raw data and reproduction, not
automated batch import — the SAME two-writer-threads mechanism as
content/labs/false-sharing, which made the identical methodology choice.
Java is the measured side for this lab's publication numbers; the Rust
track builds an equivalent evidence binary (covering both shapes) and
runs it separately (rust.md) — no cross-harness ranking is published.

## Required metrics

Bytes/record (this lab's hand-verified, cross-language byte-identical
stride constants — see java.md/rust.md `size_of` assertions), ns/access
(sequential benchmarks' own reported ns/op, divided by record count),
cache-line crossings and unaligned-load evidence (`perf stat`
cache-misses/dTLB-load-misses wrapping the pinned worker process,
`scripts/performance-lab/labs/struct-layout-alignment.conf`,
`LAB_PERF_EVENTS`), throughput in ops/ms
(`producerConsumerCounters`' own JMH `Mode.Throughput` output).

## Profiler evidence

JOL's `ClassLayout` output is the primary, always-available footprint
and field-offset evidence for `producerConsumerCounters` — it reads the
JVM's actual object layout, never an estimate, and is what revealed this
lab's central finding (java.md). `perf stat`/`perfasm` supplement the
sequential datasets' cache/TLB evidence where the host supports them.
Rust's `cargo asm` (repository-supported, capability-detected) is
recorded as unavailable rather than substituted where the host does not
support it.

## A real finding from development wiring (dev-only, never published)

Running `JolReport` on this repository's development machine showed the
JVM placing `PoorFieldOrder`'s and `OptimizedFieldOrder`'s cold `int`
fields BEFORE the `producerCount`/`consumerCount` fields declared FIRST
in source — direct, concrete confirmation that the JVM does not respect
field declaration order for ordinary objects, this lab's "assuming field
declaration order is universal" trap made literal. The practical
consequence, confirmed by the two-thread JMH throughput benchmark:
`natural`, `poorFieldOrder` and `optimizedFieldOrder` clustered together
(≈61,172–71,222 combined ops/ms) while only `cacheLineAligned` (≈325,301
ops/ms, ≈4.6–5.3× higher) showed real separation. Running the Rust
evidence binary's equivalent two-thread throughput measurement showed
the identical direction (`cacheLineAligned` ≈2.3× `natural`'s combined
throughput), confirming the mechanism (only explicit padding/alignment
defeats false sharing; field reordering does not) holds in both
languages even though the measured magnitude differed. For the
sequential datasets, `mixed_record::Natural`'s measured Rust size (24
bytes, Rust's default layout auto-optimizing) versus the Java FFM
track's hand-computed, never-auto-optimized "natural" size (32 bytes)
was this lab's other headline cross-language finding (rust.md). None of
these exact numbers are published evidence; the directions and
mechanisms are what this lab's real, reproduced evidence is checked
against.

## Raw data and reproduction

```bash
# Correctness gates (both languages) — run before trusting any timing:
cd content/labs/struct-layout-alignment/code/java && mvn test
cd content/labs/struct-layout-alignment/code/rust && cargo test

# Dev wiring checks (never publication-eligible):
mvn -q -DskipTests package
java -cp target/benchmarks.jar pl.kzybala.lab.structlayout.JolReport
java -jar target/benchmarks.jar StructLayoutLinuxEvidenceBenchmark \
  -p variant=cacheLineAligned -p dataset=headerPlusPayload
cargo run --release --bin struct_layout_alignment_evidence -- \
  --variant cacheLineAligned --dataset headerPlusPayload

# producerConsumerCounters evidence (manual pinned run — not part of the
# automated batch; see the "operation definitions" note above):
java -jar target/benchmarks.jar CounterLayoutLinuxEvidenceBenchmark \
  -p variant=cacheLineAligned -t 2 -Dplab.cpuA=<CPU_A> -Dplab.cpuB=<CPU_B>

# Publication evidence (dedicated native-Linux host only, sequential
# datasets; see docs/linux-evidence-runner.md for host setup and CPU
# selection):
./scripts/performance-lab/run-linux-evidence.sh struct-layout-alignment \
  --profile publication --cpus <CPU_A>

# Focused single-variant rerun (diagnosis):
./scripts/performance-lab/run-linux-evidence.sh struct-layout-alignment \
  --profile smoke --cpus <CPU_A> --variant aligned-header
```

Raw JMH/Criterion output, JOL reports, perf stat CSVs, placement
evidence and environment metadata are produced per variant by the
runner and imported through the canonical result pipeline — numbers are
never transcribed into this page by hand.

## Limitations

- "assuming field declaration order is universal" is a named trap this
  lab's whole methodology exists to prevent — and its own JOL evidence
  demonstrates the trap concretely rather than only warning about it in
  the abstract.
- "unsafe references to packed fields" is a named trap: neither
  language's Java/Rust code in this lab contains an unaligned reference;
  every packed-field access is a by-value read (java.md, rust.md).
- "padding away footprint without measuring array density" is a named
  trap: `cacheLineAligned`'s larger per-record stride is disclosed
  alongside its throughput win, never presented as a footprint
  improvement — it is explicitly the opposite trade-off.
- "using 64-byte lines as universal without documenting host" is a named
  trap: this lab's `cacheLineAligned` variants assume a 64-byte cache
  line explicitly, stated here and in theory.md — the actual value on
  the native-Linux publication host must be confirmed
  (`getconf LEVEL1_DCACHE_LINESIZE` or equivalent) before any
  `cacheLineAligned` conclusion is trusted for that host.
- No result on this page is a Java-versus-Rust performance claim
  (non-goal by policy); each language's default-layout behavior and its
  explicit-layout tools are disclosed on their own terms, never merged
  into a single winner claim.
