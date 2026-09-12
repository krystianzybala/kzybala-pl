# Allocation cost and object layout — benchmark methodology

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist, but no canonical evidence from the dedicated native-Linux
benchmark host has been imported for this laboratory yet — no verified
performance conclusion is available. Development-machine runs validate
wiring and correctness only and are never published as measured results
(`docs/measurement-environments.md`).

## What is deterministic vs what is measured

- **Deterministic** — every representation variant's checksum over a
  given dataset's identical value stream: representation must never
  change the result (`code/fixtures/allocation-object-layout-fixtures.json`).
- **Measured** — bytes/logical-record, B/op, allocation rate, cache
  misses and construction ns/record, for each of the 12
  (variant × dataset) cells, only from the native-Linux evidence runner
  and this lab's real JOL evidence (which runs on any JVM, including
  development machines, since it introspects real object layout rather
  than estimating it).

## Operation definitions (the contract)

| Measured artifact | One "operation" | Oracle |
|---|---|---|
| `construct` | build a fresh representation from source data and sum it — this IS the timed construction cost | the variant's checksum matches the fixture-pinned value for the declared dataset |
| `sumHot` | read back one already-built, persistent representation and sum it — construction excluded | same |

`reusedMutableHolder` has no separate "build once, read many" phase —
`construct` and `sumHot` measure the identical underlying stream for
this variant (java.md, rust.md), a documented, honest design trade-off,
not a comparability gap. Every variant is `kind=jmh` in the runner
configuration; `AllocationObjectLayoutLinuxEvidenceBenchmark` runs BOTH
JMH `@Benchmark` methods for every (variant, dataset) cell
(`scripts/performance-lab/labs/allocation-object-layout.conf`). Java is
the measured side for this lab's publication numbers; the Rust track
builds an equivalent evidence binary and runs it separately (rust.md) —
no cross-harness ranking is published.

## Required metrics

Bytes/logical-record (JOL `GraphLayout`/`ClassLayout`, captured directly,
not estimated), B/op and allocation rate (JMH `-prof gc` on the
`construct` benchmark), cache misses (`perf stat` wrapping the pinned
worker process,
`scripts/performance-lab/labs/allocation-object-layout.conf`,
`LAB_PERF_EVENTS`) and construction ns/record (`construct`'s own
reported ns/op, divided by the dataset's record count).

## Profiler evidence

`JolReport`'s own output is the primary, always-available footprint
evidence — it reads the JVM's actual object layout, never an estimate,
and runs on any JVM including a development machine. JMH's `-prof gc`
supplements it with allocation-rate and B/op evidence during
`construct`. `async-profiler`'s allocation mode and a heap profiler are
recorded as unavailable rather than substituted where the host does not
support them (`docs/measurement-environments.md`).

## A real finding from development wiring (dev-only, never published)

Running `JolReport` on this repository's development machine showed the
header-overhead ratio worsening as payload size shrank and reference
count grew: 1.83× for `ordersQuotes` (24 packed bytes/record), 3.50× for
`smallTuples` (8 packed bytes/record, no references), and 4.00× —
the worst of the three — for `treeNodes` (8 packed bytes/record, but two
reference fields). Running the Java JMH dev benchmark showed
`packedOffHeapStruct`'s `sumHot` (403,119 ns) SLOWER than
`boxedObjectGraph`'s (172,375 ns) on `ordersQuotes` despite being 45%
smaller in bytes — real evidence that byte density and read speed are
different axes, this lab's own performance question. Running the Rust
evidence binary across the identical dataset showed the same qualitative
ordering (packed slower to read than flat/reused) at roughly two to
three orders of magnitude smaller absolute cost, and a genuinely
surprising result: Rust's `boxedObjectGraph` `construct` (≈21.6 ns/record)
was slower per record than Java's (≈4.2 ns/record) despite paying zero
header bytes — the JVM's TLAB allocation beating repeated individual
`malloc` calls for many small objects on this run. None of these exact
numbers are published evidence; the directions and mechanisms are what
this lab's real, reproduced evidence is checked against.

## Raw data and reproduction

```bash
# Correctness gates (both languages) — run before trusting any timing:
cd content/labs/allocation-object-layout/code/java && mvn test
cd content/labs/allocation-object-layout/code/rust && cargo test

# Dev wiring checks (never publication-eligible):
mvn -q -DskipTests package
java -cp target/benchmarks.jar pl.kzybala.lab.objectlayout.JolReport
java -jar target/benchmarks.jar AllocationObjectLayoutLinuxEvidenceBenchmark \
  -p variant=packedOffHeapStruct -p dataset=treeNodes -prof gc
cargo run --release --bin allocation_object_layout_evidence -- \
  --variant packedOffHeapStruct --dataset treeNodes --op sumHot

# Publication evidence (dedicated native-Linux host only; see
# docs/linux-evidence-runner.md for host setup and CPU selection):
./scripts/performance-lab/run-linux-evidence.sh allocation-object-layout \
  --profile publication --cpus <CPU_A>

# Focused single-variant rerun (diagnosis):
./scripts/performance-lab/run-linux-evidence.sh allocation-object-layout \
  --profile smoke --cpus <CPU_A> --variant packed-tree
```

Raw JMH/Criterion output, JOL reports, perf stat CSVs, placement evidence
and environment metadata are produced per variant by the runner and
imported through the canonical result pipeline — numbers are never
transcribed into this page by hand.

## Limitations

- "ignoring alignment" is a named trap this lab's whole methodology
  exists to prevent: `packedOffHeapStruct`'s fields are ordered
  widest-first and every field keeps its own natural alignment, on both
  languages' sides.
- "using packed unaligned fields unsafely" is a named trap: neither
  language's packed variant uses unsafe, unaligned access —
  Java's `MemorySegment.get/set` and Rust's `to_ne_bytes`/`from_ne_bytes`
  are both safe regardless of the buffer's own alignment.
- "comparing object graph with reduced functionality" is a named trap:
  `treeNodes`' boxed variant is a genuine linked graph with real
  pointer-chasing traversal in both languages, never simplified to index
  arithmetic wearing a tree's name.
- "measuring only construction" is a named trap directly addressed by
  this lab's `construct`/`sumHot` split — a representation's construction
  cost and its read cost are reported and read separately, never
  conflated into one number.
- No result on this page is a Java-versus-Rust performance claim
  (non-goal by policy); Rust's zero-header-overhead structural advantage
  and its measured, real allocation cost are both disclosed, never
  merged into a single winner claim.
