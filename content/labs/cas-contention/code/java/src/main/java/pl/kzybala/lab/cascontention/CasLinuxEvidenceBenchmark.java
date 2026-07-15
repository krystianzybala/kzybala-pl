package pl.kzybala.lab.cascontention;

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Publication-evidence benchmark for the native-Linux evidence runner:
 * N symmetric contenders (selected per scenario by the runner via
 * {@code -t}) retrying CAS on ONE shared counter, each contender pinned to
 * its own physical core ({@code -Dplab.workerCpus=<csv>}, worker i → i-th
 * CPU), against the single-writer baseline ({@code -p
 * scenario=singleWriter}, always {@code -t 1}).
 *
 * <p>Retry policies ({@code -p backoff=...}), applied only after a FAILED
 * CAS — the uncontended fast path is identical across policies:
 * <ul>
 *   <li>{@code none} — immediate retry with {@link Thread#onSpinWait()};</li>
 *   <li>{@code fixed} — park 1 µs per failure;</li>
 *   <li>{@code expjitter} — exponential backoff (1 µs base, ×2 per
 *     consecutive failure, 64 µs cap) with deterministic xorshift jitter
 *     seeded by the worker index — no shared RNG, no
 *     {@code ThreadLocalRandom} in the measured path, and the jitter
 *     sequence is reproducible from the recorded seed.</li>
 * </ul>
 *
 * <p>Per-thread successful operations and failed attempts are reported two
 * ways: as JMH {@link AuxCounters} (aggregated secondary metrics in the
 * JMH JSON) and as per-worker progress files
 * ({@code cas-progress-<pid>-w<idx>.json}) for the fairness/progress
 * distribution — plus worker-placement evidence via {@link WorkerPin}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class CasLinuxEvidenceBenchmark {

    @Param({"cas", "singleWriter"})
    public String scenario;

    @Param({"none", "fixed", "expjitter"})
    public String backoff;

    private final AtomicLong shared = new AtomicLong();
    private long plain; // singleWriter baseline: exactly one thread, no atomics

    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class Contender {
        /** CAS successes (or plain increments for the baseline). */
        public long successes;
        /** Failed CAS attempts (retries = failures; retries-per-success is failures/successes). */
        public long failures;

        int workerIndex;
        WorkerPin pin;
        long jitterState;
        int failureStreak;
        String backoffPolicy;
        boolean singleWriter;

        @Setup(Level.Trial)
        public void setUp(CasLinuxEvidenceBenchmark bench, ThreadParams threads) {
            workerIndex = threads.getThreadIndex();
            backoffPolicy = bench.backoff;
            singleWriter = bench.scenario.equals("singleWriter");
            if (singleWriter && threads.getThreadCount() != 1) {
                throw new IllegalStateException("singleWriter baseline requires exactly 1 thread, got " + threads.getThreadCount());
            }
            // Deterministic per-worker jitter seed — recorded, reproducible.
            jitterState = 0x9E3779B97F4A7C15L ^ (workerIndex + 1);
            if (WorkerPin.pinningRequested()) {
                Integer cpu = WorkerPin.cpuForWorkerIndex(workerIndex);
                if (cpu == null) {
                    throw new IllegalStateException("pinning requested but no CPU for worker " + workerIndex);
                }
                pin = WorkerPin.establish("worker" + workerIndex, cpu);
            }
        }

        @Setup(Level.Iteration)
        public void resetCounters() {
            successes = 0;
            failures = 0;
        }

        void backoffAfterFailure() {
            switch (backoffPolicy) {
                case "none" -> Thread.onSpinWait();
                case "fixed" -> LockSupport.parkNanos(1_000);
                case "expjitter" -> {
                    if (failureStreak < 6) failureStreak++; // cap: 1µs << 6 = 64µs
                    long capNanos = 1_000L << failureStreak;
                    // xorshift64* — cheap, deterministic, thread-local
                    jitterState ^= jitterState >>> 12;
                    jitterState ^= jitterState << 25;
                    jitterState ^= jitterState >>> 27;
                    long jitter = Long.remainderUnsigned(jitterState * 0x2545F4914F6CDD1DL, capNanos / 2);
                    LockSupport.parkNanos(capNanos / 2 + jitter);
                }
                default -> throw new IllegalStateException("unknown backoff: " + backoffPolicy);
            }
        }

        @TearDown(Level.Trial)
        public void record() {
            if (pin != null) pin.verifyAndRecord();
            String dir = System.getProperty("plab.placementDir");
            if (dir == null) return;
            String json = "{ \"worker\": " + workerIndex
                + ", \"successes\": " + successes
                + ", \"failures\": " + failures
                + ", \"backoff\": \"" + backoffPolicy + "\""
                + ", \"jitterSeed\": \"0x" + Long.toHexString(0x9E3779B97F4A7C15L ^ (workerIndex + 1)) + "\" }\n";
            try {
                Files.writeString(
                    Path.of(dir, "cas-progress-" + ProcessHandle.current().pid() + "-w" + workerIndex + ".json"),
                    json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                throw new IllegalStateException("cannot write per-worker progress artifact", e);
            }
        }
    }

    /**
     * One operation = one successful counter increment (retrying failed
     * CAS attempts inside the operation, applying the selected policy
     * after each failure and resetting its streak on success).
     */
    @Benchmark
    public void increment(Contender contender) {
        if (contender.singleWriter) {
            plain++;
            contender.successes++;
            return;
        }
        for (;;) {
            long current = shared.get();
            if (shared.compareAndSet(current, current + 1)) {
                contender.successes++;
                contender.failureStreak = 0;
                return;
            }
            contender.failures++;
            contender.backoffAfterFailure();
        }
    }

    @TearDown(Level.Trial)
    public void validate() {
        if (scenario.equals("cas") && shared.get() == 0) {
            throw new IllegalStateException("shared counter never advanced — contenders were starved; run is invalid");
        }
        if (scenario.equals("singleWriter") && plain == 0) {
            throw new IllegalStateException("baseline counter never advanced; run is invalid");
        }
    }
}
