# UDP ingest, batching and packet loss — Java

## Wire format

```java
public final class DatagramFormat {
    public static final int SEQUENCE_SIZE = 8; // 8-byte little-endian sequence prefix
    public static byte[] build(long sequence, int datagramSize) { /* deterministic payload */ }
    public static boolean payloadMatches(byte[] buf, int offset, int length, long sequence) { /* ... */ }
}
```

Every variant sends/receives this same layout, over UDP loopback only —
`LoadGenerator` connects exclusively to `InetAddress.getLoopbackAddress()`.

## Raw receive variants

```java
public long receiveAllocatingPerPacket(long messageCount, int datagramSize) throws IOException {
    for (long i = 0; i < messageCount; i++) {
        ByteBuffer buf = ByteBuffer.allocateDirect(datagramSize); // fresh allocation every call
        channel.receive(buf);
        // validate and discard
    }
}

public long receiveWithReusedBuffer(long messageCount, int datagramSize) throws IOException {
    ByteBuffer buf = ByteBuffer.allocateDirect(datagramSize); // allocated once
    for (long i = 0; i < messageCount; i++) {
        buf.clear();
        channel.receive(buf);
        // validate and discard
    }
}
```

Both variants have no downstream handoff at all — no queue, no second
thread — isolating raw receive-loop cost (and, for the first variant, its
per-receive allocation) from handoff cost.

## Copying handoff

```java
public IngestResult run(long messageCount, int datagramSize) throws Exception {
    ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(queueCapacity); // BOUNDED
    Thread consumer = new Thread(() -> { /* drains queue, validates, counts delivered/corrupted */ });
    consumer.start();

    ByteBuffer buf = ByteBuffer.allocateDirect(datagramSize);
    for (long i = 0; i < messageCount; i++) {
        buf.clear();
        channel.receive(buf);
        byte[] copy = new byte[buf.remaining()]; buf.get(copy); // one allocation + copy per datagram
        if (!queue.offer(copy)) {
            applicationDropped.incrementAndGet(); // explicit, counted — never silent
        }
    }
    // ... poison-pill shutdown, await consumer
}
```

`queue.offer(...)` (non-blocking) rather than `queue.put(...)` (blocking)
is the deliberate choice that turns "queue full" into an explicit,
counted drop instead of blocking the receive loop — a blocked receive
loop would eventually cause real kernel-level socket backup, which is
exactly the confusion this lab's accounting is designed to avoid.

## Zero-copy view handoff with a bounded lifetime

```java
ByteBuffer[] slab = new ByteBuffer[slabSize]; // slabSize == queue capacity
boolean[] inFlight = new boolean[slabSize];

for (long i = 0; i < messageCount; i++) {
    int idx = (int) (i % slabSize);
    if (inFlight[idx]) {
        // Not yet freed by the consumer — receiving into it now would
        // violate the bounded-lifetime contract.
        channel.receive(discardBuf);        // still drains the socket
        applicationDropped.incrementAndGet(); // explicit drop, not silent
        continue;
    }
    slab[idx].clear();
    channel.receive(slab[idx]);   // ZERO-COPY: OS writes directly into the slab slot
    inFlight[idx] = true;
    readyIndices.put(idx);        // hand the CONSUMER an index, not a copy
    // reclaim any slots the consumer has since finished with
}
```

The check `if (inFlight[idx])` happens **before** the receive call, not
after — the specific ordering that prevents ever overwriting a slot the
consumer might still be reading (the "reusing receive buffer after
publication" trap). Because `slabSize` equals the bounded queue's
capacity, at most `slabSize` distinct indices can ever be outstanding at
once, so this check is sufficient on its own to guarantee safety without
any additional locking between the receiver and consumer threads.

## Correctness gate

`UdpIngestOperationsTest` sends real UDP datagrams over loopback and
asserts, for both handoff pipelines: every sent message is accounted for
(`delivered + applicationDropped + corrupted == messageCount`), zero
corruption, full delivery when the queue/slab is generously sized, and
explicit, non-zero application drops (still fully accounted for) when it
is deliberately undersized against a burst load. `mvn test` runs this
suite before any benchmark output is trusted.

## JMH benchmark

`UdpIngestBenchmark` runs each of the four measurable variants (batched
receive is capability-unavailable — see theory.md) across the three
datagram-size profiles, spawning a load generator on a background thread
per invocation. An earlier version of this benchmark leaked its sender
`ExecutorService` across trials (no `@TearDown`), which left the forked
JVM unable to exit cleanly after JMH's own benchmark loop finished
("did not exit, are there stray running threads?") — fixed by shutting
the executor down explicitly in `@TearDown(Level.Trial)`.

The runnable Maven/JMH project (with correctness tests under
`src/test/java/`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/udp-ingest-batching/code/java" rel="noopener"><code>content/labs/udp-ingest-batching/code/java/</code></a>
in this site's repository.
