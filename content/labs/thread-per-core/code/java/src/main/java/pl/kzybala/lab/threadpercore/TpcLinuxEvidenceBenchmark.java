package pl.kzybala.lab.threadpercore;

import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
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
import org.openjdk.jmh.infra.ThreadParams;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Publication-evidence benchmark for the native-Linux runner: the same
 * business operation — "record one event in a partition" — under three
 * ownership disciplines, N pinned workers (worker i → i-th entry of
 * {@code -Dplab.workerCpus}):
 *
 * <ul>
 *   <li>{@code partitioned} — worker i owns partition i exclusively: a
 *     plain increment on a line-padded slot (no lock, no RMW — the
 *     thread-per-core discipline);</li>
 *   <li>{@code sharedPool} — every worker records into the shared,
 *     lock-protected pool ({@link SharedCounterPool}) — the worker-pool
 *     baseline;</li>
 *   <li>{@code hotPartition} — 80% of every worker's events target
 *     partition 0, the rest its own partition (deterministic per-worker
 *     xorshift, recorded seed): the skewed-key case partitioning alone
 *     cannot fix; atomic increments keep counts exact under the
 *     multi-writer hot key.</li>
 * </ul>
 *
 * Per-worker progress is reported as {@link AuxCounters} and as
 * per-worker progress files (fairness distribution) — cross-core handoff
 * and bounded backpressure live in the separate
 * {@link TpcHandoffBackpressureHarness} (kind=aux in the runner config).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class TpcLinuxEvidenceBenchmark {

    static final int MAX_PARTITIONS = 16;
    static final int STRIDE = 16; // 16 longs = 128 bytes between owned slots

    @Param({"partitioned", "sharedPool", "hotPartition"})
    public String scenario;

    // padded owner-only slots (plain writes — exactly one writer each)
    private long[] ownedSlots;
    // atomic slots for the multi-writer hot-partition scenario
    private AtomicLongArray atomicSlots;
    private SharedCounterPool pool;

    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class Worker {
        /** Events recorded by THIS worker (per-core progress). */
        public long events;

        int index;
        long jitterState;
        WorkerPin pin;

        @Setup(Level.Trial)
        public void setUp(ThreadParams threads) {
            index = threads.getThreadIndex();
            if (index >= MAX_PARTITIONS) {
                throw new IllegalStateException("thread count exceeds MAX_PARTITIONS");
            }
            jitterState = 0x9E3779B97F4A7C15L ^ (index + 1); // recorded, deterministic
            if (WorkerPin.pinningRequested()) {
                Integer cpu = WorkerPin.cpuForWorkerIndex(index);
                if (cpu == null) throw new IllegalStateException("no CPU for worker " + index);
                pin = WorkerPin.establish("worker" + index, cpu);
            }
        }

        @Setup(Level.Iteration)
        public void reset() {
            events = 0;
        }

        boolean nextIsHot() {
            jitterState ^= jitterState >>> 12;
            jitterState ^= jitterState << 25;
            jitterState ^= jitterState >>> 27;
            // 80/20: hot when the mixed value lands in the lower 4/5 of the range
            return Long.remainderUnsigned(jitterState * 0x2545F4914F6CDD1DL, 5) != 0;
        }

        @TearDown(Level.Trial)
        public void record() {
            if (pin != null) pin.verifyAndRecord();
        }
    }

    @Setup(Level.Trial)
    public void setUp() {
        ownedSlots = new long[(MAX_PARTITIONS + 2) * STRIDE];
        atomicSlots = new AtomicLongArray((MAX_PARTITIONS + 2) * STRIDE);
        pool = new SharedCounterPool(MAX_PARTITIONS);
    }

    /** One operation = record one event in a partition. */
    @Benchmark
    public void recordEvent(Worker worker) {
        switch (scenario) {
            case "partitioned" -> ownedSlots[(worker.index + 1) * STRIDE]++;
            case "sharedPool" -> pool.increment(worker.index);
            case "hotPartition" -> {
                int partition = worker.nextIsHot() ? 0 : worker.index;
                atomicSlots.incrementAndGet((partition + 1) * STRIDE);
            }
            default -> throw new IllegalStateException("unknown scenario: " + scenario);
        }
        worker.events++;
    }

    @TearDown(Level.Trial)
    public void validate() {
        long total = 0;
        for (int p = 0; p < MAX_PARTITIONS; p++) {
            total += ownedSlots[(p + 1) * STRIDE] + atomicSlots.get((p + 1) * STRIDE) + pool.get(p);
        }
        if (total == 0) {
            throw new IllegalStateException("no events recorded in any discipline — run is invalid");
        }
    }
}
