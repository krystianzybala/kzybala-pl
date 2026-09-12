# Memory-mapped files and page faults — Java

## The shared record format

```java
public final class RecordFile {
    public static final int RECORD_SIZE = 64; // 8-byte id + 8-byte value + 48-byte payload
    public static void write(Path path, long recordCount) throws IOException { /* ... */ }
    public static long expectedValue(long id) { return id * 2654435761L + 1; }
    public static byte expectedPayloadByte(long id, int index) { return (byte) ((id + index) & 0xFF); }
    public static long checksumRecord(long id, long value, byte[] payload) { /* ... */ }
}
```

Every variant in this lab reads or writes this exact 64-byte layout — see
the [Object Serialization vs Fixed Binary Layout](/lab/fixed-binary-serialization/)
lab for why a fixed offset table matters for direct field access.

## Buffered read

```java
public long readAllChecksum(Path path, long recordCount) throws IOException {
    ByteBuffer buf = ByteBuffer.allocate(RecordFile.RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
        for (long i = 0; i < recordCount; i++) {
            buf.clear();
            while (buf.hasRemaining()) channel.read(buf); // one explicit read() per record
            buf.flip();
            // decode id/value/payload, fold into checksum
        }
    }
    return checksum;
}
```

`FileChannel.read(ByteBuffer)` is an explicit system call per record —
this is the baseline every mmap variant is measured against.

## Mmap sequential and random read

```java
public MappedByteBuffer mapFreshly(Path path, long fileSize) throws IOException {
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
        MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
        mapped.order(ByteOrder.LITTLE_ENDIAN);
        return mapped;
    }
}
```

`FileChannel.map` never issues a per-record system call — the mapped
region simply appears in the process's address space, and the JVM/OS
resolve individual page faults transparently as the code below touches
each page for the first time:

```java
public long readSequentialChecksum(MappedByteBuffer mapped, long recordCount) {
    MappedByteBuffer buf = mapped.duplicate();
    buf.order(ByteOrder.LITTLE_ENDIAN); // duplicate() must be re-pinned to LE explicitly
    buf.clear();
    for (long i = 0; i < recordCount; i++) {
        long id = buf.getLong();
        long value = buf.getLong();
        buf.get(payload);
        checksum += RecordFile.checksumRecord(id, value, payload);
    }
    return checksum;
}
```

`readRandomChecksum` performs the identical decode using absolute
`get(offset)` calls in a caller-supplied permutation order instead of
sequential relative reads — same bytes, different page-touch order, which
is exactly the variable this lab's random-access variant isolates.

## Warm vs cold, as this benchmark can honestly produce them

`MmapBenchmark` warms one `MappedByteBuffer` once, untimed, in
`@Setup(Level.Trial)`, then measures `warmMmapSequentialRead` against that
already-touched mapping repeatedly. `coldMappedSequentialRead` maps the
*same file* freshly on every single invocation and reads it once — no
reused `MappedByteBuffer`, no prior touch by this benchmark method. As
theory.md explains, this does not prove the underlying OS pages were
actually evicted from the page cache; it isolates "cost of establishing a
fresh mapping and its first traversal" from "cost of reading an
already-mapped, already-warm buffer," which is the honest, capability-real
distinction a development machine without root can produce.

## Mapped write and flush

```java
public MappedByteBuffer mapForWrite(Path path, long fileSize) throws IOException {
    try (FileChannel channel = FileChannel.open(path, READ, WRITE, CREATE)) {
        channel.truncate(fileSize);
        MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_WRITE, 0, fileSize);
        mapped.order(ByteOrder.LITTLE_ENDIAN);
        return mapped;
    }
}

public void writeAll(MappedByteBuffer mapped, long recordCount) { /* writes every record's bytes */ }
public void flush(MappedByteBuffer mapped) { mapped.force(); } // forces dirty pages to storage
```

`writeAll` and `flush` are separate methods precisely so the benchmark can
measure them as separate costs — the write loop touches memory only; only
`force()` actually forces storage I/O.

## Correctness gate

`MmapOperationsTest` writes the small `fixedRecords` fixture (4,096
records), then asserts that buffered read, mmap sequential read, mmap
random read (over a deterministic permutation), and a full
mmap-write-then-read-back round trip all reproduce the exact same
checksum. It also asserts the permutation helper visits every index
exactly once and is deterministic across calls. `mvn test` runs this
suite before any benchmark output is trusted.

## JMH benchmark

`MmapBenchmark` runs over the lab's primary 64 MiB / 1,048,576-record
dataset, with one `@Benchmark` method per variant (`bufferedRead`,
`warmMmapSequentialRead`, `coldMappedSequentialRead`, `randomMappedAccess`,
`mappedWriteAndFlush`), each doing one full pass per invocation.

The runnable Maven/JMH project (with correctness tests under
`src/test/java/`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/memory-mapped-files/code/java" rel="noopener"><code>content/labs/memory-mapped-files/code/java/</code></a>
in this site's repository.
