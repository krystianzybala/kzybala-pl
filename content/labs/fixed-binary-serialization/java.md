# Object serialization vs fixed binary layout — Java

## The shared message shape

```java
public record EventRecord(
        byte version, long id, int deviceId, byte opcode, byte flags,
        double value, long timestamp, int sampleCount, int[] samples,
        boolean optionalPresent, int optionalField) {
    public static final int MAX_SAMPLES = 8;
    public static final int WIRE_SIZE = 70; // see code for the exact field-by-field derivation
}
```

All three Java variants encode/decode this one record; only the wire
representation and the decode-time materialization strategy differ.

## Generic object codec

```java
public byte[] encode(EventRecord event) {
    ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
    try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
        oos.writeObject(SerializableEvent.fromRecord(event));
    } catch (IOException e) { throw new UncheckedIOException(e); }
    return bos.toByteArray();
}
```

`ObjectOutputStream` walks `SerializableEvent`'s fields reflectively and
writes a class descriptor (class name, `serialVersionUID`, field
descriptors) before the field values themselves — every message re-states
its own shape. This is not a criticism of JDK serialization as a general
tool (it solves problems this lab doesn't have, like arbitrary object
graphs and cross-JVM class evolution); it is exactly the "generic object
codec" baseline the fixed-layout variants are measured against, and the
only generic serializer already available in this repository without
adding a new dependency.

## ByteBuffer/manual codec

```java
public byte[] encode(EventRecord event) {
    ByteBuffer buf = ByteBuffer.allocate(EventRecord.WIRE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    buf.put(event.version());
    buf.putLong(event.id());
    buf.putInt(event.deviceId());
    // ... one put call per field, at implicit sequential offsets
    return buf.array();
}
```

No class descriptor, no reflection: every field is written by name, in a
fixed order, into a buffer sized exactly to `WIRE_SIZE`. Byte order is
pinned explicitly to `LITTLE_ENDIAN` — the "hardcoding endianness
silently" trap this lab documents is about never declaring an order, not
about picking one. Decode still allocates one fresh `EventRecord` (and its
backing `samples` array) per call: this variant removes the
schema-discovery cost, not the per-decode object-allocation cost.

## FFM flyweight codec

```java
public EventView decode(byte[] wire) {
    Arena arena = Arena.ofAuto();
    MemorySegment segment = arena.allocate(EventRecord.WIRE_SIZE);
    MemorySegment.copy(wire, 0, segment, ValueLayout.JAVA_BYTE, 0, wire.length);
    return new EventView(segment);
}
```

`EventView` holds only a `MemorySegment` reference; each accessor
(`id()`, `deviceId()`, `value()`, ...) reads its field directly from the
segment at a fixed offset, computed on the call, not decoded up front:

```java
public int deviceId() {
    return segment.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), OFF_DEVICE_ID);
}
```

A caller that only needs `deviceId()` out of a batch of decoded messages
never pays to materialize the `samples` array or box a `Double`. Copying
the incoming `byte[]` into the segment is still one copy this lab does not
avoid (the caller-owned array is not addressable memory the segment can
alias without pinning); what the flyweight removes is the *per-field*
materialization on top of that one copy, matching the same trade-off
Rust's borrowed `EventView` makes against Rust's owned decode. `EventView`
and `ByteBufferCodec` are asserted byte-for-byte wire-compatible by the
correctness suite — they must produce identical bytes for identical input,
since both implement the same fixed layout independently.

## Correctness gate

`BinSerOperationsTest` asserts, for every one of the four fixture profiles
(small command, medium event, repeated fields, versioned optional field):
round-trip equality for all three codecs, exact `WIRE_SIZE` for the two
fixed-layout variants, byte-for-byte wire equality between ByteBuffer and
FFM, and that the versioned-optional-field slot reads back correctly
whether or not the message actually uses it. `mvn test` runs this suite
before any benchmark output is trusted.

## JMH benchmark

`BinSerBenchmark` parameterizes over all four dataset profiles
(`@Param`) and benchmarks encode/decode for each of the three Java
variants, plus a `ffmDecodeFieldOnly` variant that reads only
`deviceId()` through the flyweight view without ever calling
`toRecord()` — the specific cost the flyweight is meant to avoid paying.

The runnable Maven/JMH project (with correctness tests under
`src/test/java/`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/fixed-binary-serialization/code/java" rel="noopener"><code>content/labs/fixed-binary-serialization/code/java/</code></a>
in this site's repository.
