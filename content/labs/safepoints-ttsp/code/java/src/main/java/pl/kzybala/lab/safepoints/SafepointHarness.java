package pl.kzybala.lab.safepoints;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The real evidence harness for this lab: application stopped time is
 * measured directly from JFR's own safepoint events
 * ({@code jdk.SafepointBegin}'s duration IS the JVM's own definition of
 * time-to-safepoint; {@code jdk.ExecuteVMOperation}'s duration is the
 * safepoint operation itself; {@code jdk.SafepointEnd}'s duration is the
 * resume phase — all three correlated by {@code safepointId}), never
 * estimated from wall-clock deltas around a benchmark call. This is a
 * {@code main()}-based aux harness, the same category of tool
 * content/labs/clocks-latency-histograms and
 * content/labs/deoptimization-uncommon-traps use for phenomena a JMH
 * throughput/avgtime mode would misrepresent — here, a genuinely
 * multi-threaded, JVM-coordinated stop-the-world event.
 */
public final class SafepointHarness {

    private SafepointHarness() {}

    /** One correlated safepoint episode, built from events sharing a safepointId. */
    private record Episode(long safepointId, long ttspNanos, long opNanos, long endNanos, long totalThreadCount, String operation) {
        long stoppedNanos() {
            return ttspNanos + opNanos + endNanos;
        }
    }

    private static final class TriggerResult {
        volatile long sum;
        volatile int safepointsTriggered;
    }

    private static Thread triggerWorker(long start, long count, int numChunks, boolean allocationPressure, boolean nativeSleepPerChunk, TriggerResult result) {
        return new Thread(() -> {
            long chunkSize = count / numChunks;
            long sum = 0;
            int triggered = 0;
            for (int c = 0; c < numChunks; c++) {
                long chunkStart = start + (long) c * chunkSize;
                sum += SafepointFixtures.sumRange(chunkStart, chunkSize);
                if (nativeSleepPerChunk) {
                    NativeSleep.sleepMicros(SafepointFixtures.NATIVE_SLEEP_MICROS);
                }
                if (allocationPressure) {
                    // Organic GC trigger: allocate garbage instead of an
                    // explicit request. How many (if any) GCs this
                    // actually provokes is host/heap-dependent by design.
                    byte[] garbage = new byte[4 * 1024 * 1024];
                    garbage[0] = 1;
                } else {
                    System.gc();
                    triggered++;
                }
            }
            result.sum = sum;
            result.safepointsTriggered = triggered;
        }, "trigger-worker");
    }

    private static Thread backgroundFleetWorker(long start, long count, TriggerResult result) {
        return new Thread(() -> result.sum = SafepointFixtures.sumRange(start, count), "fleet-worker");
    }

    private static Thread idleWorker(AtomicBoolean stop) {
        return new Thread(() -> {
            while (!stop.get()) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "idle-worker");
    }

    private static Thread nativeBlockedWorker(AtomicBoolean stop) {
        return new Thread(() -> {
            while (!stop.get()) {
                NativeSleep.sleepMicros(20_000);
            }
        }, "native-blocked-worker");
    }

    public static void main(String[] args) throws Exception {
        String variant = "cooperativeLoop";
        String dataset = "numericLoop";
        for (int i = 0; i < args.length - 1; i++) {
            if ("--variant".equals(args[i])) variant = args[i + 1];
            if ("--dataset".equals(args[i])) dataset = args[i + 1];
        }
        boolean allocationPressure = "allocationPressureTrigger".equals(variant);
        boolean extraNativeThread = "threadInNativeCall".equals(variant);
        boolean extraIdleThreads = "manyIdleThreads".equals(variant);
        boolean nativeSleepPerChunk = "nativeSleepDowncall".equals(dataset);

        List<Thread> mainWorkers = new ArrayList<>();
        List<TriggerResult> mainResults = new ArrayList<>();
        List<Thread> extraWorkers = new ArrayList<>();
        AtomicBoolean stopExtras = new AtomicBoolean(false);

        switch (dataset) {
            case "numericLoop", "nativeSleepDowncall" -> {
                TriggerResult r = new TriggerResult();
                mainResults.add(r);
                mainWorkers.add(triggerWorker(0, SafepointFixtures.TOTAL_ITERATIONS, SafepointFixtures.NUM_CHUNKS,
                    allocationPressure, nativeSleepPerChunk, r));
            }
            case "threadFleet" -> {
                TriggerResult leaderResult = new TriggerResult();
                mainResults.add(leaderResult);
                mainWorkers.add(triggerWorker(0, SafepointFixtures.PER_FLEET_WORKER_ITERATIONS, SafepointFixtures.NUM_CHUNKS,
                    allocationPressure, nativeSleepPerChunk, leaderResult));
                for (int w = 1; w < SafepointFixtures.FLEET_SIZE; w++) {
                    TriggerResult r = new TriggerResult();
                    mainResults.add(r);
                    mainWorkers.add(backgroundFleetWorker((long) w * SafepointFixtures.PER_FLEET_WORKER_ITERATIONS,
                        SafepointFixtures.PER_FLEET_WORKER_ITERATIONS, r));
                }
            }
            default -> throw new IllegalArgumentException("unknown dataset: " + dataset);
        }

        if (extraNativeThread) {
            Thread t = nativeBlockedWorker(stopExtras);
            t.setDaemon(true);
            extraWorkers.add(t);
        }
        if (extraIdleThreads) {
            for (int i = 0; i < SafepointFixtures.IDLE_THREAD_COUNT; i++) {
                Thread t = idleWorker(stopExtras);
                t.setDaemon(true);
                extraWorkers.add(t);
            }
        }

        Path jfrOut = Files.createTempFile("safepoints-ttsp-", ".jfr");
        Recording recording = new Recording();
        recording.enable("jdk.SafepointBegin");
        recording.enable("jdk.SafepointStateSynchronization");
        recording.enable("jdk.SafepointEnd");
        recording.enable("jdk.ExecuteVMOperation");
        recording.setDestination(jfrOut);
        recording.start();

        for (Thread t : extraWorkers) t.start();
        Thread.sleep(50); // let extras reach their steady blocked/idle state before triggering
        for (Thread t : mainWorkers) t.start();
        for (Thread t : mainWorkers) t.join();

        stopExtras.set(true);
        for (Thread t : extraWorkers) t.join(1000);

        recording.stop();
        recording.close();

        long actualTotal = 0;
        for (TriggerResult r : mainResults) actualTotal += r.sum;
        if (actualTotal != SafepointFixtures.EXPECTED_TOTAL) {
            throw new IllegalStateException(
                "correctness oracle failed for variant=" + variant + " dataset=" + dataset
                    + ": expected " + SafepointFixtures.EXPECTED_TOTAL + " got " + actualTotal);
        }

        List<Episode> episodes = correlateEpisodes(jfrOut);
        Files.deleteIfExists(jfrOut);

        printReport(variant, dataset, actualTotal, episodes);
    }

    private static List<Episode> correlateEpisodes(Path jfrFile) throws Exception {
        Map<Long, long[]> byId = new HashMap<>(); // [ttsp, op, end, totalThreadCount]
        Map<Long, String> opNameById = new HashMap<>();
        for (RecordedEvent e : RecordingFile.readAllEvents(jfrFile)) {
            String type = e.getEventType().getName();
            long id = e.getLong("safepointId");
            long[] slot = byId.computeIfAbsent(id, k -> new long[4]);
            switch (type) {
                case "jdk.SafepointBegin" -> {
                    slot[0] = e.getDuration().toNanos();
                    slot[3] = e.getLong("totalThreadCount");
                }
                case "jdk.ExecuteVMOperation" -> {
                    if (e.getBoolean("safepoint")) {
                        slot[1] = e.getDuration().toNanos();
                        opNameById.put(id, e.getString("operation"));
                    }
                }
                case "jdk.SafepointEnd" -> slot[2] = e.getDuration().toNanos();
                default -> { /* jdk.SafepointStateSynchronization: informational only, not needed for the three-phase split */ }
            }
        }
        List<Episode> episodes = new ArrayList<>();
        for (var entry : byId.entrySet()) {
            long[] v = entry.getValue();
            episodes.add(new Episode(entry.getKey(), v[0], v[1], v[2], v[3], opNameById.getOrDefault(entry.getKey(), "unknown")));
        }
        episodes.sort((a, b) -> Long.compare(a.safepointId(), b.safepointId()));
        return episodes;
    }

    private static long percentile(long[] sorted, double frac) {
        if (sorted.length == 0) return 0;
        int idx = Math.min(sorted.length - 1, (int) (sorted.length * frac));
        return sorted[idx];
    }

    private static void printReport(String variant, String dataset, long checksum, List<Episode> episodes) {
        long[] ttsp = episodes.stream().mapToLong(Episode::ttspNanos).sorted().toArray();
        long[] stopped = episodes.stream().mapToLong(Episode::stoppedNanos).sorted().toArray();
        long maxThreadCount = episodes.stream().mapToLong(Episode::totalThreadCount).max().orElse(0);

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"harness\": \"SafepointHarness\",\n");
        sb.append("  \"variant\": \"").append(variant).append("\",\n");
        sb.append("  \"dataset\": \"").append(dataset).append("\",\n");
        sb.append("  \"checksum\": ").append(checksum).append(",\n");
        sb.append("  \"safepointEpisodes\": ").append(episodes.size()).append(",\n");
        sb.append("  \"maxThreadCount\": ").append(maxThreadCount).append(",\n");
        sb.append("  \"ttspNanos\": { \"p50\": ").append(percentile(ttsp, 0.50))
            .append(", \"p99\": ").append(percentile(ttsp, 0.99))
            .append(", \"max\": ").append(ttsp.length == 0 ? 0 : ttsp[ttsp.length - 1]).append(" },\n");
        sb.append("  \"stoppedNanos\": { \"p50\": ").append(percentile(stopped, 0.50))
            .append(", \"p99\": ").append(percentile(stopped, 0.99))
            .append(", \"max\": ").append(stopped.length == 0 ? 0 : stopped[stopped.length - 1]).append(" }\n");
        sb.append("}");
        System.out.println(sb);
    }
}
