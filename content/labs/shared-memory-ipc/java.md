# Shared-memory IPC — Java

## Header and slot layout

```java
public final class SharedRing {
    public static final int PROTOCOL_VERSION = 1;
    public static final int HEADER_SIZE = 192;   // capacity, protocolVersion, writerSeq, readerSeq (cache-line separated)
    public static final int SLOT_SIZE = 1024;    // 4-byte slot header + up to 1020 bytes payload
    // ...
}
```

`writerSeq` (offset 64) and `readerSeq` (offset 128) each get their own
64-byte-separated region — the same cache-line-separation idea the
[False Sharing](/lab/false-sharing/) lab teaches, now applied to a
cross-process shared segment where a false-sharing-induced coherence
traffic cost would be paid by two separate CPU cores backing two separate
processes.

## Creating and opening the segment

```java
public static SharedRing createNew(Path path, int capacity) throws IOException {
    try (FileChannel channel = FileChannel.open(path, READ, WRITE, CREATE, TRUNCATE_EXISTING)) {
        channel.truncate(segmentSize(capacity));
        MemorySegment mapped = channel.map(FileChannel.MapMode.READ_WRITE, 0, size, Arena.ofShared());
        mapped.set(LE_INT, OFF_CAPACITY, capacity);
        mapped.set(LE_INT, OFF_PROTOCOL_VERSION, PROTOCOL_VERSION);
        // writerSeq, readerSeq initialized to 0
        return new SharedRing(mapped, capacity);
    }
}

public static SharedRing openExisting(Path path) throws IOException {
    // maps just the header first, checks protocolVersion, rejects a mismatch explicitly
    // before mapping the full segment and trusting any other field
}
```

`openExisting` reads `protocolVersion` before trusting `capacity` or
anything else — an explicit, checked rejection rather than the "ignoring
version mismatch" trap this lab documents. `Arena.ofShared()` (rather than
`Arena.ofConfined()`) is required here because the mapping must remain
valid and usable from whichever thread/process context calls into it
across the object's lifetime, not just the thread that created it.

## Cursor fields: release/acquire across a process boundary

```java
private static final ValueLayout.OfLong LE_LONG = ValueLayout.JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN);
// NOT JAVA_LONG_UNALIGNED — VarHandle access modes beyond plain get/set
// (setRelease/getAcquire) require a naturally-aligned layout; the header
// offsets (64, 128) are always 8-byte aligned because mmap returns
// page-aligned base addresses.

private void setWriterSeqRelease(long value) {
    LE_LONG.varHandle().setRelease(segment, OFF_WRITER_SEQ, value);
}
private long writerSeqAcquire() {
    return (long) LE_LONG.varHandle().getAcquire(segment, OFF_WRITER_SEQ);
}
```

This is exactly the [SPSC Ring Buffer](/lab/spsc-ring-buffer/) lab's
`setRelease`/`getAcquire` pairing — the difference is what visibility it
is establishing: there, visibility between two threads in one JVM; here,
visibility between two independent JVM processes each with their own
`MemorySegment` mapping of the same physical pages.

## Publish, batch publish, consume, and the zero-copy view

```java
public boolean tryPublish(byte[] payload, int length) { /* reserve -> write slot -> release-store writerSeq */ }
public int tryPublishBatch(byte[][] payloads, int[] lengths) { /* several slots, ONE writerSeq update */ }
public int tryConsume(byte[] out) { /* acquire-load writerSeq -> copy slot into out -> release-store readerSeq */ }
public SlotView tryConsumeView() { /* same cursor logic, but returns a view instead of copying */ }
```

`SlotView` mirrors the flyweight pattern from the
[Object Serialization vs Fixed Binary Layout](/lab/fixed-binary-serialization/)
lab: its accessors (`length()`, `byteAt(i)`) read directly from the
mapped segment on demand, so a consumer that only needs a few bytes never
pays to copy the whole slot — this is the "shared memory slot view"
variant.

## The socket baseline

```java
public long runOnce(long messageCount, int payloadSize) throws Exception {
    // a loopback ServerSocket/Socket pair; every message crosses the real
    // kernel network stack, exactly as it would between two processes
}
```

Two threads, not two processes — but sockets have no same-process-threads
trap the way shared heap memory does: the kernel enforces the identical
syscall/copy path whether the two ends are threads or processes, so this
is a fair baseline despite not spawning a second OS process.

## The real cross-process proof

```java
// ProducerMain: java -cp <classpath> pl.kzybala.lab.shmipc.ProducerMain <segmentPath> <capacity> <messageCount> <payloadSize>
// ConsumerMain: java -cp <classpath> pl.kzybala.lab.shmipc.ConsumerMain <segmentPath> <messageCount> <payloadSize>
```

`ProcessLauncherTest` launches both as genuinely separate OS processes via
`ProcessBuilder`, waits for the segment file to reach its expected size
before starting the consumer, and asserts every message round-trips
correctly — this is the actual proof this lab's "missing cross-process
memory-order proof" trap requires, distinct from the same-process-threads
`SharedRingOperationsTest` (which verifies protocol *logic* using two
independent `SharedRing` mappings of the same file within one JVM — closer
to the real mechanism than sharing one object, but still not a substitute
for real separate processes). A second test,
`restartedConsumerResumesWithoutLossOrDuplication`, kills a consumer after
it reads only part of the stream and starts a fresh one that resumes from
the segment's own recorded `readerSeq` — the restart/recovery scenario.

## Correctness gate

`mvn test` runs both `SharedRingOperationsTest` (fast, in-process protocol
logic) and `ProcessLauncherTest` (slower, spawns real JVMs) — both must
pass before any benchmark output is trusted.

## JMH benchmark

`ShmIpcBenchmark` compares `socketBaseline`, `sharedMemoryCopyPayload`,
`sharedMemorySlotView`, and `sharedMemoryBatched` across the three payload
profiles (32B/128B/1KiB), using two independent mappings of the same
backing file within the benchmark process (documented above as distinct
from the real cross-process proof).

The runnable Maven/JMH project (with correctness tests under
`src/test/java/`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/shared-memory-ipc/code/java" rel="noopener"><code>content/labs/shared-memory-ipc/code/java/</code></a>
in this site's repository.
