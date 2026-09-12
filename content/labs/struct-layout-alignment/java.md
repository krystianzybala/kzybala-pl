# Struct layout, alignment and padding — Java track

Package `pl.kzybala.lab.structlayout`: `StructLayoutFixtures`
(deterministic source-data generation), `MixedRecordLayout` and
`HeaderPayloadLayout` (five `MemorySegment`-based layout variants each),
`ProducerConsumerCounters` (five plain-object layout variants, reusing
content/labs/false-sharing's mechanism), and `JolReport` (real
heap-layout evidence for the counter classes).

## The pieces

- **`MixedRecordLayout` / `HeaderPayloadLayout`** — every variant is a
  `MemorySegment` with byte offsets computed once and accessed via plain
  `segment.get/set(ValueLayout, long offset)` calls (the offset-based
  technique content/labs/aos-vs-soa's java.md documents avoiding a real
  ~100x C2 regression from layout-path `VarHandle`s). Unlike a plain JVM
  object, `MemorySegment` NEVER reorders — declared offsets are exactly
  what gets used, which is why these two datasets can demonstrate real,
  controllable field-order effects at all.
- **`packedUnaligned`'s two fields need `ValueLayout`'s `*_UNALIGNED`
  variants** (`JAVA_LONG_UNALIGNED`, `JAVA_INT_UNALIGNED`,
  `JAVA_SHORT_UNALIGNED`) — the default, ALIGNED `ValueLayout`s throw
  `IllegalArgumentException` on a misaligned offset rather than silently
  reading past a boundary; this repository discovered that constraint
  directly while building this layout (see below).
- **`ProducerConsumerCounters`** — five plain Java classes (`Natural`,
  `PoorFieldOrder`, `OptimizedFieldOrder`, `CacheLineAligned`,
  `PackedUnaligned`), each with one dedicated writer thread per counter
  (no data race — content/labs/false-sharing). `CacheLineAligned` is
  `content/labs/false-sharing`'s `PaddedCounters` technique exactly (7
  unused `long`s between the counters).
- **`JolReport`** — real per-instance heap layout evidence via JOL,
  confirming (or correcting) this lab's assumptions about how the JVM
  actually lays out `ProducerConsumerCounters`' five classes.

## A real bug found while building this lab

`headerPlusPayload`'s `msgFlags` field has a fixture range of 0..255 —
it does not fit in a signed Java `byte` (-128..127). The first version
of `HeaderPayloadLayout.sum()` read it back with plain
`segment.get(ValueLayout.JAVA_BYTE, ...)` and added the result directly
to a `long` sum; Java's byte-to-long widening SIGN-EXTENDS, so values
128..255 came back as large negative numbers, silently corrupting the
checksum for roughly half of all generated records. The fix masks the
read with `& 0xFF` before widening. This is now documented explicitly
in `HeaderPayloadLayout`'s `sum()` method and is itself a small,
concrete instance of this lab's broader theme: a byte's *size* (1 byte)
and its *value range* (0..255 vs. -128..127) are two different
questions, and getting them confused corrupts data even when the layout
itself (the offset, the alignment) is completely correct.

## A real finding from development wiring (dev-only, never published)

Running `JolReport` on this repository's development machine produced
the single most important, and most surprising, result in this lab:
**the JVM does not respect field declaration order for `PoorFieldOrder`
or `OptimizedFieldOrder`.** Both classes declare `producerCount` FIRST
in source, yet JOL shows the JVM placing the unrelated `coldA` field
BEFORE `producerCount` in the actual object layout in both cases — the
JVM's default packing algorithm groups fields by size regardless of
declared order. The practical consequence: `Natural`, `PoorFieldOrder`
and `OptimizedFieldOrder` all place `producerCount`/`consumerCount`
at the SAME relative offsets (16/24), meaning all three are
functionally identical for coherence purposes, confirmed directly by
the JMH throughput numbers below. Running the two-thread throughput
benchmark (dev machine, 1 warmup/1 measurement iteration — illustrative
only) showed `cacheLineAligned` at ≈325,301 combined ops/ms versus
`natural` (≈61,172), `poorFieldOrder` (≈71,222), `optimizedFieldOrder`
(≈67,509) and `packedUnaligned` (≈68,317) all clustered in the same
much-lower range — a roughly 4.6–5.3× throughput gap that lines up
exactly with JOL's structural finding: only `cacheLineAligned`'s
explicit, JVM-unoptimizable padding actually separates the two counters
onto different cache lines. None of these exact numbers are published
evidence; the directions and the mechanism (JVM field reordering
defeats source-level "field order" as a technique for plain objects) are
what benchmark.md's real evidence is checked against.

## Build, correctness gate, run

```bash
cd content/labs/struct-layout-alignment/code/java

# correctness gate — every variant sums to the identical total per dataset;
# every counter variant counts exactly right under real concurrency
mvn test

# build
mvn -q -DskipTests package

# real heap-layout evidence for the counter classes (any JVM)
java -cp target/benchmarks.jar pl.kzybala.lab.structlayout.JolReport

# dev/wiring JMH: mixedPrimitiveRecord sequential access (unpinned)
java -jar target/benchmarks.jar StructLayoutBenchmark

# dev/wiring JMH: producerConsumerCounters throughput (unpinned, 2 threads)
java -jar target/benchmarks.jar CounterLayoutBenchmark -t 2

# THE full-matrix publication benchmarks (pinned, via the native-Linux runner)
java -jar target/benchmarks.jar StructLayoutLinuxEvidenceBenchmark \
  -p variant=cacheLineAligned -p dataset=headerPlusPayload
java -jar target/benchmarks.jar CounterLayoutLinuxEvidenceBenchmark \
  -p variant=cacheLineAligned -t 2
```

## Reading the results

- Always check `JolReport`'s ACTUAL offsets before drawing a conclusion
  from a plain-object variant's throughput number — this lab's own
  finding is that source order and physical order can diverge, and
  assuming they match is exactly the "assuming field declaration order
  is universal" trap.
- For `mixedPrimitiveRecord`/`headerPlusPayload`, compare bytes/record
  (this lab's hand-verified, cross-language-matched stride constants)
  alongside ns/record — `packedUnaligned` being smallest does not
  automatically mean fastest; read both numbers, never one alone.
- For `producerConsumerCounters`, treat `natural`, `poorFieldOrder` and
  `optimizedFieldOrder` as one group and `cacheLineAligned` as the only
  variant expected to differ meaningfully — a result showing otherwise
  is worth investigating with `JolReport` directly, not assumed away.
