# Syscall amortization and scatter/gather I/O — Java

## Wire format and accounting

```java
public final class MessageFormat {
    public static final int HEADER_SIZE = 16; // 8-byte sequence + 8-byte payloadLength, both LE
}

public record WriteAccounting(long writeCalls, long bytesWritten, long partialWrites, long messagesSent) {}
```

`writeCalls` is an honest application-level proxy for kernel crossings —
see theory.md's "counting application calls instead of syscalls" trap.

## Single small write and coalesced buffer

```java
// Single small write: two separate write() calls per message.
for (ByteBuffer buf : new ByteBuffer[]{header, payload}) {
    while (buf.hasRemaining()) {
        int n = channel.write(buf);   // loop handles partial writes correctly
        writeCalls++;
    }
}

// Coalesced buffer: one copy, then one write() call.
byte[] combined = new byte[header.length + payload.length];
System.arraycopy(header, 0, combined, 0, header.length);
System.arraycopy(payload, 0, combined, header.length, payload.length);
// ... one write() loop over `combined`
```

## Scatter/gather write

```java
ByteBuffer[] buffers = { headerBuf, payloadBuf };
while (remaining(buffers) > 0) {
    long n = channel.write(buffers); // ONE gathering write() call, no copy
    writeCalls++;
}
```

`SocketChannel.write(ByteBuffer[])` (the `GatheringByteChannel` interface)
writes from both buffers in a single call, and — critically for partial
writes — each `ByteBuffer`'s own `position()` advances exactly as far as
that buffer's bytes were actually consumed, so simply looping while any
buffer `hasRemaining()` and calling `write(buffers)` again is already
correct partial-write handling; no manual bookkeeping is required on the
Java side.

## Size-bounded batch

```java
List<ByteBuffer> batch = new ArrayList<>();
for (int i = 0; i < n; i++) {
    batch.add(ByteBuffer.wrap(MessageFormat.buildHeader(seq + i, payloadSize)));
    batch.add(ByteBuffer.wrap(MessageFormat.buildPayload(seq + i, payloadSize)));
}
ByteBuffer[] buffers = batch.toArray(new ByteBuffer[0]);
while (remaining(buffers) > 0) {
    channel.write(buffers); // ONE gathering write() call for the WHOLE batch
    writeCalls++;
}
```

Because every message's header and payload are still separate buffers
within the same gathering write, batching adds zero extra copies on top
of scatter/gather — it only adds the accumulation delay theory.md
describes.

## Backpressured receiver scenario

```java
public static long receiveSlowly(SocketChannel channel, long messageCount, long perMessageDelayMillis) { /* ... */ }
```

Pairing `Receiver.receiveSlowly` (an artificial per-message delay) with
`Senders.scatterGatherWrite` on a small-buffered socket
(`setSendBufferSize`/`setReceiveBufferSize`) reliably induces real
partial writes on the sender side — `SyscallAmortOperationsTest`'s
`backpressuredReceiverStillDeliversEveryMessageDespiteInducedPartialWrites`
verifies every byte still arrives correctly despite them.

## Correctness gate

`SyscallAmortOperationsTest` sends real messages over TCP loopback and
asserts, for each variant: full delivery, the expected write-call count
(2/message for single-small-write, 1/message for coalesced/scatter-gather,
far fewer than 1/message for the batch), and correct behavior under
induced backpressure. `mvn test` runs this suite before any benchmark
output is trusted.

## JMH benchmark

`SyscallAmortBenchmark` runs the four throughput-measurable variants
(the backpressured-receiver scenario is a correctness/resilience proof,
not a throughput comparison) across the three dataset profiles.

The runnable Maven/JMH project (with correctness tests under
`src/test/java/`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/syscall-amortization-scatter-gather/code/java" rel="noopener"><code>content/labs/syscall-amortization-scatter-gather/code/java/</code></a>
in this site's repository.
