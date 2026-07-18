package pl.kzybala.lab.spscringbuffer;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.infra.Control;
import org.openjdk.jmh.infra.ThreadParams;

import java.util.concurrent.TimeUnit;

/**
 * Publication-evidence benchmark for the native-Linux evidence runner:
 * one PERSISTENT producer worker and one PERSISTENT consumer worker (JMH
 * group threads live for the whole trial — no thread creation anywhere
 * near the measured path), one physical core per worker (pinned via
 * {@code -Dplab.cpuA}/{@code -Dplab.cpuB}, verified in
 * worker-placement.json), fixed power-of-two capacity, preallocated
 * payload/output storage, zero allocation in the measured operations.
 *
 * <p>Operation definition (shared with the Rust persistent-worker harness
 * {@code spsc_evidence} — methodology parity is what makes the two
 * comparable): one producer operation = exactly one item accepted into the
 * ring (spinning on a full ring inside the operation); the consumer drains
 * up to {@code batch} items per operation with one cursor acknowledgement,
 * verifying the monotonic sequence of every item it reads. Steady-state
 * transfer rate is the producer side's throughput — the producer cannot
 * outrun the consumer by more than the fixed capacity.
 *
 * <p>Scenario matrix (selected per JVM invocation by the runner, never
 * mixed): {@code cursorMode} cached|uncached × {@code batch} 1|64 ×
 * {@code capacity} 1024|65536.
 */
@State(Scope.Group)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class SpscLinuxEvidenceBenchmark {

    @Param({"cached", "uncached"})
    public String cursorMode;

    @Param({"1", "64"})
    public int batch;

    @Param({"1024", "65536"})
    public int capacity;

    private SpscRingBuffer buffer;
    private long produceSeq;
    private long consumeSeq;
    private long[] out; // preallocated consumer storage — no allocation in the measured path
    private volatile boolean sequenceViolated;

    /**
     * Explicit SPSC event counters (JMH aux counters, per worker). These —
     * not the primary invocation throughput — are the authoritative
     * item-rate evidence: with {@link Control}-bounded loops one successful
     * invocation is one produced item (or one drained batch), but the final
     * invocation of an iteration may return without transferring anything,
     * so published items/s always comes from producedItems/consumedItems.
     */
    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class ProducerCounters {
        public long producedItems;
        public long producerFullRetries;

        @Setup(Level.Iteration)
        public void reset() {
            producedItems = 0;
            producerFullRetries = 0;
        }
    }

    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class ConsumerCounters {
        public long consumedItems;
        public long consumerEmptyPolls;
        public long sequenceViolations;

        @Setup(Level.Iteration)
        public void reset() {
            consumedItems = 0;
            consumerEmptyPolls = 0;
            sequenceViolations = 0;
        }
    }

    @State(Scope.Thread)
    public static class PinProducer {
        WorkerPin pin;

        @Setup(Level.Trial)
        public void pin() {
            if (WorkerPin.pinningRequested()) pin = WorkerPin.establish("producer", WorkerPin.CPU_A);
        }

        @TearDown(Level.Trial)
        public void verify() {
            if (pin != null) pin.verifyAndRecord();
        }
    }

    @State(Scope.Thread)
    public static class PinConsumer {
        WorkerPin pin;

        @Setup(Level.Trial)
        public void pin() {
            if (WorkerPin.pinningRequested()) pin = WorkerPin.establish("consumer", WorkerPin.CPU_B);
        }

        @TearDown(Level.Trial)
        public void verify() {
            if (pin != null) pin.verifyAndRecord();
        }
    }

    @Setup(Level.Trial)
    public void setUp(ThreadParams threads) {
        if (threads.getGroupThreadCount() != 2) {
            throw new IllegalStateException("SPSC evidence requires exactly 2 group threads (one producer, one consumer) — run with -t 2");
        }
        buffer = new SpscRingBuffer(capacity, switch (cursorMode) {
            case "cached" -> true;
            case "uncached" -> false;
            default -> throw new IllegalStateException("unknown cursorMode: " + cursorMode);
        });
        produceSeq = 0;
        consumeSeq = 0;
        out = new long[batch];
        sequenceViolated = false;
    }

    /**
     * One successful operation = exactly one item accepted. The retry loop
     * is bounded by {@link Control#stopMeasurement}: at iteration end the
     * consumer may already have parked on JMH's iteration latch with the
     * ring full — the batch-20260717T150131Z hang — so an unbounded
     * spin-until-accepted here deadlocks the fork. When JMH signals the
     * end, this method returns {@code false} without producing; the
     * producer and consumer are independently capable of returning, never
     * assuming the other stops at the same instant.
     */
    @Benchmark
    @Group("transfer")
    public boolean produce(Control control, PinProducer pinned, ProducerCounters counters) {
        while (!control.stopMeasurement) {
            if (buffer.tryProduce(produceSeq)) {
                produceSeq++;
                counters.producedItems++;
                return true;
            }
            counters.producerFullRetries++;
            Thread.onSpinWait();
        }
        return false;
    }

    /**
     * One operation = up to {@code batch} items drained with one
     * acknowledgement; every item's monotonic sequence is verified — the
     * same per-item compare the Rust harness performs, part of the shared
     * operation definition rather than benchmark overhead.
     */
    @Benchmark
    @Group("transfer")
    public int consume(Control control, PinConsumer pinned, ConsumerCounters counters) {
        while (!control.stopMeasurement) {
            int n = buffer.tryConsumeBatch(out, batch);
            if (n > 0) {
                for (int i = 0; i < n; i++) {
                    if (out[i] != consumeSeq + i) {
                        sequenceViolated = true;
                        counters.sequenceViolations++;
                    }
                }
                consumeSeq += n;
                counters.consumedItems += n;
                return n;
            }
            counters.consumerEmptyPolls++;
            Thread.onSpinWait();
        }
        return 0;
    }

    @TearDown(Level.Trial)
    public void validate() {
        if (sequenceViolated) {
            throw new IllegalStateException("sequence violation observed (stale read or overwrite) — run is invalid");
        }
        if (consumeSeq == 0 || produceSeq == 0) {
            throw new IllegalStateException("no items transferred (produced=" + produceSeq + ", consumed=" + consumeSeq + ") — a worker was starved");
        }
        // In-flight items at iteration shutdown are EXPECTED, not a
        // violation: the ring is trial-scoped, so items queued when an
        // iteration ends are drained (sequence-checked) at the start of
        // the next one, and at trial teardown at most `capacity` items may
        // legitimately remain unconsumed.
        long inFlight = produceSeq - consumeSeq;
        if (inFlight < 0 || inFlight > capacity) {
            throw new IllegalStateException("cursor accounting broken: produced=" + produceSeq + ", consumed=" + consumeSeq + ", capacity=" + capacity);
        }
    }
}
