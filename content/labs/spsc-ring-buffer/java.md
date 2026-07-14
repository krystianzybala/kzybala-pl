# SPSC ring buffer — Java

## Zero-allocation ring buffer

```java
public final class SpscRingBuffer {
    private static final VarHandle HEAD;
    private static final VarHandle TAIL;
    static {
        try {
            HEAD = MethodHandles.lookup().findVarHandle(SpscRingBuffer.class, "head", long.class);
            TAIL = MethodHandles.lookup().findVarHandle(SpscRingBuffer.class, "tail", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final long[] slots;
    private final int mask;
    private volatile long head = 0; // published cursor
    private volatile long tail = 0; // acknowledged cursor
    private long cachedTail = 0, reserveIndex = 0; // producer-only
    private long cachedHead = 0, readIndex = 0;    // consumer-only

    public SpscRingBuffer(int capacity) {
        if (Integer.bitCount(capacity) != 1) throw new IllegalArgumentException("capacity must be a power of two");
        this.slots = new long[capacity];
        this.mask = capacity - 1;
    }

    public boolean tryProduce(long value) {
        int capacity = slots.length;
        if (reserveIndex - cachedTail == capacity) {
            cachedTail = (long) TAIL.getAcquire(this);
            if (reserveIndex - cachedTail == capacity) return false; // genuinely full
        }
        slots[(int) (reserveIndex & mask)] = value; // payload write — not yet visible
        reserveIndex++;
        HEAD.setRelease(this, reserveIndex); // publication
        return true;
    }

    public boolean tryConsume(long[] out) {
        if (readIndex == cachedHead) {
            cachedHead = (long) HEAD.getAcquire(this);
            if (readIndex == cachedHead) return false; // genuinely empty
        }
        out[0] = slots[(int) (readIndex & mask)]; // payload read
        readIndex++;
        TAIL.setRelease(this, readIndex); // consumption acknowledgement
        return true;
    }
}
```

No boxing, no allocation, on either `tryProduce`/`tryConsume` call —
`out` is caller-allocated once and reused, matching this site's
Performance Lab convention of zero-allocation steady-state examples. The
public API collapses the theory's five phases into two calls
(`tryProduce` does reserve→write→publish internally, `tryConsume` does
read→acknowledge), but the *internal ordering* — write strictly before
`setRelease` — is exactly what the theory insists on; the interactive
model above exposes each phase as its own step purely for teaching
purposes.

`HEAD`/`TAIL` use `VarHandle.setRelease`/`getAcquire` rather than plain
`volatile` reads/writes on `head`/`tail`, because only the release/acquire
pairing is actually required here (see the
[Memory Ordering](/lab/memory-ordering/) lab): the producer's own reads of
`head` (via `reserveIndex`, its private counter) never need synchronization
since only the producer ever writes it, and the same holds for the
consumer and `tail`. `cachedTail`/`cachedHead` are plain (non-volatile)
`long` fields for exactly that reason — they are read and written only by
their owning thread.

## Power-of-two capacity

The constructor rejects any capacity that isn't a power of two, because
`index & mask` (a single fast bitwise AND) is only equivalent to
`index % capacity` when `capacity` is a power of two and `mask = capacity - 1`.
This is a common, deliberate constraint in real high-throughput ring
buffers (including the LMAX Disruptor), not an oversight — pick the next
power of two above your actual required capacity.

## JMH benchmark

```java
@State(Scope.Group)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class SpscRingBufferBenchmark {
    private final SpscRingBuffer buffer = new SpscRingBuffer(1024);
    private final long[] out = new long[1];

    @Benchmark @Group("spsc") @GroupThreads(1)
    public void produce() {
        while (!buffer.tryProduce(1)) Thread.onSpinWait();
    }

    @Benchmark @Group("spsc") @GroupThreads(1)
    public void consume() {
        while (!buffer.tryConsume(out)) Thread.onSpinWait();
    }
}
```

`@Group("spsc")` with one `@GroupThreads(1)` benchmark method for each side
is JMH's supported way to force two `@Benchmark` methods to run
concurrently, as a real pipeline, rather than measuring each in isolation —
the same pattern the False Sharing lab's Java example uses to force paired
concurrent writes. Both methods spin-wait (`Thread.onSpinWait()`, a hint
the JIT/CPU can use to reduce spin-loop power draw without blocking) rather
than sleeping, so the measured throughput reflects steady-state pipeline
speed once both threads are running, not scheduling latency.

The runnable Maven/JMH project (with correctness tests under
`src/test/java/`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/spsc-ring-buffer/code/java" rel="noopener"><code>content/labs/spsc-ring-buffer/code/java/</code></a>
in this site's repository.
