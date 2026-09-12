# Zero-copy binary parsing and views — benchmark methodology

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist, but no canonical evidence from the dedicated native-Linux
benchmark host has been imported for this laboratory yet — no verified
performance conclusion is available. Development-machine runs validate
wiring and correctness only and are never published as measured results
(`docs/measurement-environments.md`).

## What is deterministic vs what is measured

- **Deterministic** — every decoder variant's checksum over a given
  dataset's identical wire bytes: decoding strategy must never change
  the result (`code/fixtures/zero-copy-binary-parsing-fixtures.json`);
  `mutableInPlaceUpdate`'s backing buffer is asserted byte-for-byte
  unchanged after its round trip, in both languages' test suites.
- **Measured** — ns/message, B/message, bytes copied, branch misses and
  validation cost, for each of the 15 (variant × dataset) cells, only
  from the native-Linux evidence runner.

## Operation definitions (the contract)

| Measured artifact | One "operation" | Oracle |
|---|---|---|
| `decodeAll` | one full pass over the declared dataset, decoding and summing every message | the variant's checksum matches the fixture-pinned value for the declared dataset |

Java is the measured side for this lab's publication numbers; the Rust
track builds an equivalent evidence binary and runs it separately
(rust.md) — no cross-harness ranking is published.

## Required metrics

Ns/message (`decodeAll`'s own reported ns/op, divided by message count),
B/message and bytes copied (JMH `-prof gc`'s `gc.alloc.rate.norm`,
divided by message count — a variant that never copies shows this near
zero), branch misses (`perf stat` wrapping the pinned worker process,
`scripts/performance-lab/labs/zero-copy-binary-parsing.conf`,
`LAB_PERF_EVENTS`), validation cost (the measured difference between
`validatedZeroCopyView` and a hypothetical unvalidated read — this lab
never publishes an actually-unvalidated variant, per its own "skipping
validation" trap, so validation cost is read from the SHARED cost every
non-copying variant pays, not isolated as its own cell).

## Profiler evidence

JMH's `-prof gc` is the primary allocation-rate evidence — it is what
revealed this lab's central finding (java.md). `perf`/`perfasm`/
`cargo asm` supplement it where the host supports them, particularly
for confirming that `validatedZeroCopyView`'s field reads compile to
plain bounds-checked loads with no hidden allocation call. `miri`/fuzz
testing is available as a repository-supported tool for the Rust
track's unsafe code — this lab's own Rust crate contains none (rust.md).

## A real finding from development wiring (dev-only, never published)

Running `-prof gc` on this repository's development machine showed
`validatedZeroCopyView`'s allocation rate at ≈17.4 B/op for a full
200,000-message pass — within rounding of zero — while
`objectBuildingDecoder` allocated ≈91,991,175 B/op, roughly 5.3
million times more, and ran ≈11.6× slower. Running the equivalent Rust
evidence binary showed the same qualitative pattern at smaller absolute
magnitudes (`object_building_decoder` ≈24.9× slower than
`validated_zero_copy_view`), and `utf8Field`'s `copying_decoder`
(materializing an owned `String` via `String::from_utf8`) ran ≈6.3×
slower than its zero-copy counterpart — a real, measured cost for the
exact mistake this lab's "claiming zero-copy while converting strings"
trap warns against. None of these exact numbers are published evidence;
the directions and mechanisms — validated views eliminate copies and
allocations, exactly as claimed, in both languages — are what this
lab's real, reproduced evidence is checked against.

## Raw data and reproduction

```bash
# Correctness gates (both languages) — run before trusting any timing:
cd content/labs/zero-copy-binary-parsing/code/java && mvn test
cd content/labs/zero-copy-binary-parsing/code/rust && cargo test

# Dev wiring checks (never publication-eligible):
mvn -q -DskipTests package
java -jar target/benchmarks.jar ZeroCopyBenchmark.decodeAll -prof gc
cargo run --release --bin zero_copy_binary_parsing_evidence -- \
  --variant validatedZeroCopyView --dataset utf8Field

# Publication evidence (dedicated native-Linux host only; see
# docs/linux-evidence-runner.md for host setup and CPU selection):
./scripts/performance-lab/run-linux-evidence.sh zero-copy-binary-parsing \
  --profile publication --cpus <CPU_A>

# Focused single-variant rerun (diagnosis):
./scripts/performance-lab/run-linux-evidence.sh zero-copy-binary-parsing \
  --profile smoke --cpus <CPU_A> --variant viewed-utf8
```

Raw JMH/Criterion output, perf stat CSVs, placement evidence and
environment metadata are produced per variant by the runner and imported
through the canonical result pipeline — numbers are never transcribed
into this page by hand.

## Limitations

- "returning views past buffer lifetime" is a named trap: this lab
  never returns a view whose backing buffer's lifetime it does not
  control; Java's arena-based lifetime enforcement and Rust's
  compile-time borrow checking are two structurally different, both
  real, answers (rust.md).
- "skipping validation" is a named trap: every non-`copyingDecoder`-style
  read-only variant in this lab's matrix validates bounds (and, for
  `utf8Field`, UTF-8 validity) before trusting any field — `lazyFieldDecode`
  defers WHEN validation happens, never WHETHER it happens.
- "using invalid UTF-8 unchecked" is a named trap: this lab's UTF-8
  validation (`ZeroCopyFixtures.isValidUtf8` in Java,
  `std::str::from_utf8` in Rust) is a real library-provided check in
  both languages, never approximated or skipped.
- "claiming zero-copy while converting strings" is a named trap this
  lab's own measured evidence demonstrates concretely, not just warns
  against: `validatedZeroCopyView`/`validated_zero_copy_view` never
  materialize a `String` for the checksum operation, and their measured
  allocation rate proves it.
- No result on this page is a Java-versus-Rust performance claim
  (non-goal by policy); both languages show the identical qualitative
  ranking (view > copy > object-building) at different absolute
  magnitudes, disclosed honestly rather than merged into a winner claim.
