package pl.kzybala.lab.threadpercore;

import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Cross-core handoff / bounded-backpressure harness for the native-Linux
 * evidence runner (kind=aux — queue-shaped overload behavior is not a JMH
 * throughput number). One PERSISTENT ingress worker hands timestamped
 * events to N persistent owner workers through bounded per-owner queues;
 * an offer that cannot be placed within the timeout is REJECTED and
 * counted — overload is explicit, never unbounded memory growth.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>{@code handoff} — uniform round-robin distribution: the pure
 *     cross-core handoff cost;</li>
 *   <li>{@code backpressure} — 80% of events target owner 0 (deterministic
 *     xorshift, recorded): the hot partition saturates, its queue caps,
 *     rejections and queue depth make the overload visible;</li>
 *   <li>{@code migration-diagnostic} — the handoff workload with worker
 *     pinning intentionally DISABLED: the scheduler places threads freely
 *     inside the taskset containment and every worker reports the kernel's
 *     per-thread migration count — a diagnostic of scheduler behavior,
 *     never a publication-comparable throughput scenario.</li>
 * </ul>
 *
 * <p>Metrics: processed/rejected/offered conservation (asserted),
 * per-owner progress, handoffs, sampled queue depth (max), handoff-latency
 * p50/p99 from a preallocated per-owner sample ring, worker placement and
 * per-thread migrations. Prints one JSON document; non-zero exit on any
 * accounting violation.
 *
 * <p>Usage: {@code java -cp benchmarks.jar ...TpcHandoffBackpressureHarness
 * --scenario handoff --owners 3 --capacity 1024 --seconds 5
 * --warmup-seconds 2 [--no-pin]}
 */
public final class TpcHandoffBackpressureHarness {

    private static final int LATENCY_SAMPLES = 65536;
    private static final long OFFER_TIMEOUT_MICROS = 100;

    public static void main(String[] args) throws InterruptedException {
        String scenario = "handoff";
        int owners = 3;
        int capacity = 1024;
        long seconds = 5;
        long warmupSeconds = 2;
        boolean noPin = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--scenario" -> scenario = args[++i];
                case "--owners" -> owners = Integer.parseInt(args[++i]);
                case "--capacity" -> capacity = Integer.parseInt(args[++i]);
                case "--seconds" -> seconds = Long.parseLong(args[++i]);
                case "--warmup-seconds" -> warmupSeconds = Long.parseLong(args[++i]);
                case "--no-pin" -> noPin = true;
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        final boolean hot = scenario.equals("backpressure");
        final boolean diagnostic = scenario.equals("migration-diagnostic");
        final boolean pin = !noPin && !diagnostic && WorkerPin.pinningRequested();

        @SuppressWarnings("unchecked")
        final ArrayBlockingQueue<Long>[] queues = new ArrayBlockingQueue[owners];
        for (int o = 0; o < owners; o++) queues[o] = new ArrayBlockingQueue<>(capacity);

        final long[] processed = new long[owners];
        final long[] handoffs = new long[owners];
        final long[][] latencySamples = new long[owners][LATENCY_SAMPLES];
        final int[] latencyCount = new int[owners];
        final long[] migrations = new long[owners + 1];
        final int[] observedCpu = new int[owners + 1];
        final Thread[] ownerThreads = new Thread[owners];
        // phase: 0 warmup, 1 measure, 2 stop
        final int[] phase = {0};

        for (int o = 0; o < owners; o++) {
            final int owner = o;
            ownerThreads[o] = new Thread(() -> {
                WorkerPin p = pin ? WorkerPin.establish("owner" + owner, WorkerPin.cpuForWorkerIndex(owner + 1)) : null;
                long migsAtStart = CpuAffinity.isSupported() ? CpuAffinity.threadMigrationCount() : -1;
                while (true) {
                    Long item;
                    try {
                        item = queues[owner].poll(1, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        break;
                    }
                    int currentPhase;
                    synchronized (phase) { currentPhase = phase[0]; }
                    if (item == null) {
                        if (currentPhase == 2) break;
                        continue;
                    }
                    long latency = System.nanoTime() - item;
                    if (currentPhase == 1) {
                        processed[owner]++;
                        handoffs[owner]++;
                        latencySamples[owner][latencyCount[owner] % LATENCY_SAMPLES] = latency;
                        latencyCount[owner]++;
                    }
                }
                long migsAtEnd = CpuAffinity.isSupported() ? CpuAffinity.threadMigrationCount() : -1;
                migrations[owner + 1] = (migsAtStart >= 0 && migsAtEnd >= 0) ? migsAtEnd - migsAtStart : -1;
                observedCpu[owner + 1] = CpuAffinity.isSupported() ? CpuAffinity.currentCpu() : -1;
                if (p != null) p.verifyAndRecord();
            });
            ownerThreads[o].start();
        }

        WorkerPin ingressPin = pin ? WorkerPin.establish("ingress", WorkerPin.cpuForWorkerIndex(0)) : null;
        long ingressMigsStart = CpuAffinity.isSupported() ? CpuAffinity.threadMigrationCount() : -1;
        long jitter = 0x9E3779B97F4A7C15L;
        long offered = 0;
        long rejected = 0;
        int rr = 0;
        long[] maxDepth = new long[owners];

        long warmupUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(warmupSeconds);
        while (System.nanoTime() < warmupUntil) {
            int target = rr = (rr + 1) % owners;
            queues[target].offer(System.nanoTime());
        }
        synchronized (phase) { phase[0] = 1; }
        long measureUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        long depthSampleAt = 0;
        while (System.nanoTime() < measureUntil) {
            int target;
            if (hot) {
                jitter ^= jitter >>> 12;
                jitter ^= jitter << 25;
                jitter ^= jitter >>> 27;
                target = Long.remainderUnsigned(jitter * 0x2545F4914F6CDD1DL, 5) != 0 ? 0 : 1 + (rr = (rr + 1) % (owners - 1 == 0 ? 1 : owners - 1));
                if (target >= owners) target = 0;
            } else {
                target = rr = (rr + 1) % owners;
            }
            offered++;
            boolean accepted;
            try {
                accepted = queues[target].offer(System.nanoTime(), OFFER_TIMEOUT_MICROS, TimeUnit.MICROSECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (!accepted) rejected++;
            long now = System.nanoTime();
            if (now > depthSampleAt) { // ~every 10ms: bounded-queue evidence
                for (int o = 0; o < owners; o++) maxDepth[o] = Math.max(maxDepth[o], queues[o].size());
                depthSampleAt = now + TimeUnit.MILLISECONDS.toNanos(10);
            }
        }
        synchronized (phase) { phase[0] = 2; }
        for (Thread t : ownerThreads) t.join();
        long ingressMigsEnd = CpuAffinity.isSupported() ? CpuAffinity.threadMigrationCount() : -1;
        migrations[0] = (ingressMigsStart >= 0 && ingressMigsEnd >= 0) ? ingressMigsEnd - ingressMigsStart : -1;
        observedCpu[0] = CpuAffinity.isSupported() ? CpuAffinity.currentCpu() : -1;
        if (ingressPin != null) ingressPin.verifyAndRecord();

        long totalProcessed = Arrays.stream(processed).sum();
        // conservation: everything offered in the measured window was either
        // processed, rejected, or was still queued at stop (drained after the
        // window closed → not counted as processed). Warmup items processed
        // inside the window inflate `processed` — bounded by total capacity.
        long inFlightBound = (long) owners * capacity;
        if (totalProcessed + rejected > offered + inFlightBound || offered == 0 || totalProcessed == 0) {
            System.err.println("accounting violation: offered=" + offered + " processed=" + totalProcessed
                + " rejected=" + rejected + " capacityBound=" + inFlightBound);
            System.exit(1);
        }

        StringBuilder perOwner = new StringBuilder();
        for (int o = 0; o < owners; o++) {
            if (o > 0) perOwner.append(", ");
            int n = Math.min(latencyCount[o], LATENCY_SAMPLES);
            long p50 = -1;
            long p99 = -1;
            if (n > 0) {
                long[] sorted = Arrays.copyOf(latencySamples[o], n);
                Arrays.sort(sorted);
                p50 = sorted[n / 2];
                p99 = sorted[Math.min(n - 1, (int) (n * 0.99))];
            }
            perOwner.append(String.format(
                "{\"owner\":%d,\"processed\":%d,\"handoffs\":%d,\"maxQueueDepth\":%d,\"latencyP50Ns\":%d,\"latencyP99Ns\":%d,\"migrationsDuringRun\":%d,\"observedCpuAtEnd\":%d}",
                o, processed[o], handoffs[o], maxDepth[o], p50, p99, migrations[o + 1], observedCpu[o + 1]));
        }

        System.out.println("{");
        System.out.println("  \"harness\": \"TpcHandoffBackpressureHarness (persistent ingress + owners, bounded queues)\",");
        System.out.println("  \"scenario\": \"" + scenario + "\", \"owners\": " + owners + ", \"queueCapacity\": " + capacity + ",");
        System.out.println("  \"offered\": " + offered + ", \"processed\": " + totalProcessed + ", \"rejected\": " + rejected + ",");
        System.out.println("  \"offerTimeoutMicros\": " + OFFER_TIMEOUT_MICROS + ",");
        System.out.println("  \"pinned\": " + pin + ", \"ingress\": {\"migrationsDuringRun\": " + migrations[0] + ", \"observedCpuAtEnd\": " + observedCpu[0] + "},");
        System.out.println("  \"ownersDetail\": [" + perOwner + "],");
        System.out.println("  \"note\": \"" + (diagnostic
            ? "migration-diagnostic: pinning intentionally disabled inside taskset containment — scheduler-placement evidence, never publication-comparable throughput"
            : "rejected work is explicit backpressure evidence, never silent loss") + "\"");
        System.out.println("}");
    }
}
