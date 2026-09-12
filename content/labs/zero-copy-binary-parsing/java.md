# Zero-copy binary parsing and views — Java track

Package `pl.kzybala.lab.zerocopy`: `ZeroCopyFixtures` (deterministic
source-data generation AND big-endian wire-format encoding),
`FixedHeaderDecoders` / `NestedRepeatedDecoders` / `Utf8FieldDecoders`
(five decoder variants each).

## The pieces

- **`ZeroCopyFixtures`** — generates the three datasets' values AND
  encodes them into a single contiguous `MemorySegment` "wire buffer"
  per dataset, big-endian throughout (`ValueLayout.JAVA_INT_UNALIGNED`/
  `JAVA_LONG_UNALIGNED` `.withOrder(ByteOrder.BIG_ENDIAN)` — every
  multi-byte header field sits at an offset this repository's own byte
  layout makes UNALIGNED by construction; see the real bug this caused
  below). Also provides `isValidUtf8`, a real, honest UTF-8 validity
  check used by `validatedZeroCopyView`/`lazyFieldDecode`, never
  skipped.
- **`copyingDecoder`** — copies the payload into a fresh `long[]` (or
  `byte[]`/`String` for text), then reads from the copy.
- **`objectBuildingDecoder`** — wraps every decoded value in its own
  object (a boxed `List<Long>`, a `List<SubRecord>`, or a
  `DecodedTextMessage` record wrapping a materialized `String`) — one
  more allocation layer than `copyingDecoder`.
- **`validatedZeroCopyView`** — validates frame bounds (and, for
  `utf8Field`, real UTF-8 validity) exactly once, then reads every field
  directly from the `MemorySegment` — no copy, no per-value object, and
  for `utf8Field` specifically, NO `String` is ever materialized for the
  checksum.
- **`lazyFieldDecode`** — identical to `validatedZeroCopyView`, except
  validation is deferred to first field access rather than running
  eagerly.
- **`mutableInPlaceUpdate`** — round-trips one field through an XOR
  mutation (write, verify the write landed, restore the original value)
  directly on the wire buffer, then reads like `validatedZeroCopyView`
  — the buffer is provably unchanged after the call, so its checksum
  matches the four read-only variants exactly.

## A real bug found while building this lab

The wire format's header is `msgType`(1 byte) + `seq`(4-byte BE int) +
a length/count field(4-byte BE int) — meaning `seq` always starts at
BYTE OFFSET 1, which is never a multiple of 4. The first version of
`ZeroCopyFixtures`'s encoder used ordinary, ALIGNED
`ValueLayout.JAVA_INT.withOrder(BIG_ENDIAN)` to write it, and every
single encode call threw `IllegalArgumentException: Target offset 1 is
incompatible with alignment constraint 4`. This is directly,
concretely the same lesson content/labs/struct-layout-alignment's
`packedUnaligned` variant teaches in the abstract: a byte-packed wire
protocol is essentially never naturally aligned, because protocol
designers optimize for byte density, not memory alignment. The fix uses
`ValueLayout.JAVA_INT_UNALIGNED`/`JAVA_LONG_UNALIGNED` throughout —
every multi-byte field in this lab's wire format is read and written
through the UNALIGNED layout variant, always, which is itself the
correct, general lesson for parsing any real wire format.

## A real finding from development wiring (dev-only, never published)

Running `-prof gc` against `fixedHeaderPlusVariablePayload`'s
`decodeAll` benchmark on this repository's development machine (N =
200,000 messages) produced results directly consistent with this lab's
hypothesis: `validatedZeroCopyView`'s allocation rate measured **17.4
B/op** for a full 200,000-message pass — within rounding of zero, and
its ns/op (≈2,495,314 ns, ≈12.5 ns/message) was the fastest of all five
variants. `copyingDecoder` allocated **27,200,023 B/op** (≈136
bytes/message) and ran ≈3,262,295 ns (≈16.3 ns/message) — 1.3× slower
than the view. `objectBuildingDecoder` was the clear outlier:
**91,991,175 B/op** (≈460 bytes/message, ≈3.4× `copyingDecoder`'s own
allocation) and ≈28,883,957 ns (≈144.4 ns/message) — roughly **11.6×
slower** than `validatedZeroCopyView`, confirming that wrapping every
decoded value in its own boxed object costs far more than the raw copy
it is built on. `lazyFieldDecode` matched `validatedZeroCopyView`
almost exactly (≈2,568,146 ns), since for this dataset validation
happens on the very first field read regardless — deferring it changes
nothing when every field is eventually read anyway.
`mutableInPlaceUpdate` (≈2,682,895 ns, ≈18.5 B/op) cost a small, real
premium over the read-only view — the extra write/verify/restore
round-trip, not allocation, since its own B/op stayed near zero. None of
these exact numbers are published evidence; the directions are what
benchmark.md's real evidence is checked against.

## Build, correctness gate, run

```bash
cd content/labs/zero-copy-binary-parsing/code/java

# correctness gate — every variant sums to the identical total per dataset
mvn test

# build
mvn -q -DskipTests package

# dev/wiring JMH: fixedHeaderPlusVariablePayload (unpinned), with real allocation evidence
java -jar target/benchmarks.jar ZeroCopyBenchmark.decodeAll -prof gc

# THE full-matrix publication benchmark (pinned, via the native-Linux runner)
java -jar target/benchmarks.jar ZeroCopyLinuxEvidenceBenchmark \
  -p variant=validatedZeroCopyView -p dataset=utf8Field -prof gc
```

## Reading the results

- Always read `gc.alloc.rate.norm` alongside ns/op — `validatedZeroCopyView`
  winning on BOTH axes simultaneously (fastest AND least allocation) is
  the direct evidence for this lab's hypothesis; a variant that wins one
  axis and loses the other would tell a more nuanced story worth
  investigating further.
- For `utf8Field` specifically, confirm `validatedZeroCopyView` and
  `lazyFieldDecode` never show allocation proportional to text length —
  if they did, that would mean a `String` was being materialized
  somewhere, exactly this lab's "claiming zero-copy while converting
  strings" trap.
- `objectBuildingDecoder`'s allocation being roughly proportional to
  record/word COUNT (not just message count) is itself a useful
  diagnostic — it confirms the extra cost comes from per-value wrapping,
  not from some fixed per-message overhead.
