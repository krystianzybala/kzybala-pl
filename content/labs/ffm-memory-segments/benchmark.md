# Heap vs off-heap with FFM MemorySegment — benchmark methodology

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist, but no canonical evidence from the dedicated native-Linux
benchmark host has been imported for this laboratory yet — no verified
performance conclusion is available. Development-machine runs validate
wiring and correctness only and are never published as measured results
(`docs/measurement-environments.md`).

## What is deterministic vs what is measured

- **Deterministic** — every storage variant's checksum over a given
  dataset's identical value stream: storage location must never change
  the result (`code/fixtures/ffm-memory-segments-fixtures.json`).
- **Measured** — ns/access, ns/record, allocation rate, RSS, cache
  misses and copy bytes, for each of the 15 (variant × dataset) cells,
  only from the native-Linux evidence runner.

## Operation definitions (the contract)

| Measured artifact | One "operation" | Oracle |
|---|---|---|
| `sequentialSum` | one full pass over the declared dataset, summing every record's numeric fields | the variant's checksum matches the fixture-pinned value for the declared dataset |
| `randomAccess` | K = 10,000 reads at a fixed, deterministic pseudo-random index stream (identical across every variant and both languages) | same |

`copiedBoundaryCrossing`'s operation includes an unconditional,
whole-dataset copy from off-heap to heap EVERY call, for both benchmark
methods — this is the variant's defining cost, not an optimization this
implementation missed (java.md, rust.md). `EV_SELECTOR` names the class
only, so JMH runs BOTH `@Benchmark` methods for every (variant, dataset)
cell (`scripts/performance-lab/labs/ffm-memory-segments.conf`). Java is
the measured side for this lab's publication numbers; the Rust track
builds an equivalent evidence binary and runs it separately (rust.md) —
no cross-harness ranking is published.

## Required metrics

Ns/access and ns/record (each benchmark's own reported ns/op, divided by
K or N respectively), allocation rate and copy bytes (JMH `-prof gc`,
primarily relevant to `copiedBoundaryCrossing`), RSS and cache misses
(`perf stat` wrapping the pinned worker process,
`scripts/performance-lab/labs/ffm-memory-segments.conf`,
`LAB_PERF_EVENTS`).

## Profiler evidence

JMH's `-prof gc` is the primary allocation-rate and copy-bytes evidence.
`perfasm` and `perf` supplement it where the host supports them; `miri`
is not applicable to this crate's own code (zero `unsafe` blocks) but
remains available as a repository-supported tool for other labs' unsafe
code. Where a tool is unavailable it is recorded as unavailable, never
substituted (`docs/measurement-environments.md`).

## A real finding from development wiring (dev-only, never published)

Running the Java JMH dev benchmark on `fixedRecords` showed
`heapPrimitiveArray`'s `sequentialSum` (≈0.48 ns/record) FASTER than
every off-heap variant (`confinedSegment` ≈1.58, `sharedSegment` ≈1.71,
`slicedView` ≈1.24 ns/record) — direct evidence against "off-heap is
automatically faster," this lab's first named trap. `copiedBoundaryCrossing`'s
`randomAccess` (≈141.7 ns/access) was roughly 48× slower per access than
`slicedView`'s (≈2.05 ns/access) on the identical dataset, because it
copies all 500,000 records to service just 10,000 reads. Running the
Rust evidence binary showed the identical qualitative pattern — native
typed access (`heap_primitive_array`, ≈0.34 ns/record) beating every
packed-buffer variant, and `copiedBoundaryCrossing`'s random-access cost
landing in the same order of magnitude as Java's comparable cell (≈176
vs. ≈141.7 ns/access) — despite the two languages' packed-access
mechanisms (JVM `MemorySegment` checks vs. Rust byte-slice conversions)
being structurally different. None of these exact numbers are published
evidence; the directions and mechanisms are what this lab's real,
reproduced evidence is checked against.

## Raw data and reproduction

```bash
# Correctness gates (both languages) — run before trusting any timing:
cd content/labs/ffm-memory-segments/code/java && mvn test
cd content/labs/ffm-memory-segments/code/rust && cargo test

# Dev wiring checks (never publication-eligible):
mvn -q -DskipTests package
java -jar target/benchmarks.jar FfmMemorySegmentsLinuxEvidenceBenchmark \
  -p variant=copiedBoundaryCrossing -p dataset=largeNumericBuffers -prof gc
cargo run --release --bin ffm_memory_segments_evidence -- \
  --variant copiedBoundaryCrossing --dataset largeNumericBuffers --op randomAccess

# Publication evidence (dedicated native-Linux host only; see
# docs/linux-evidence-runner.md for host setup and CPU selection):
./scripts/performance-lab/run-linux-evidence.sh ffm-memory-segments \
  --profile publication --cpus <CPU_A>

# Focused single-variant rerun (diagnosis):
./scripts/performance-lab/run-linux-evidence.sh ffm-memory-segments \
  --profile smoke --cpus <CPU_A> --variant copied-large
```

Raw JMH/Criterion output, perf stat CSVs, placement evidence and
environment metadata are produced per variant by the runner and imported
through the canonical result pipeline — numbers are never transcribed
into this page by hand.

## Limitations

- "off-heap as automatic speedup" is a named trap this lab's whole
  methodology exists to prevent: the heap array baseline wins the
  sequential-read comparison on this dev machine, not the off-heap
  variants, and this page states that plainly rather than curating
  toward the "off-heap wins" narrative.
- "closing arena during use" is a named trap: this lab's variants never
  close an arena while a slice or cross-thread reference is live; Rust's
  equivalent bug class is structurally prevented at compile time
  (rust.md), a real difference disclosed rather than glossed over.
- "cross-thread confinement violations" is a named trap: `confinedSegment`
  is only ever accessed from the thread that created it in this lab's
  code; `sharedSegment` exists specifically to test the cost of the
  alternative, never to paper over a confinement violation.
- "unsafe Rust without invariant documentation" is a named trap: this
  lab's Rust crate uses zero `unsafe` blocks (rust.md) — there is no
  invariant to document because there is no unsafe code.
- No result on this page is a Java-versus-Rust performance claim
  (non-goal by policy); Rust's structural absence of a runtime
  confinement check and its own real, measured packed-access cost are
  both disclosed, never merged into a single winner claim.
