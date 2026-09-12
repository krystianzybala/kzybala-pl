package pl.kzybala.lab.gctail;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The real evidence harness for this lab: pause p50/p99/p999, GC count
 * and live set are read directly from the JVM's own JFR GC events
 * ({@code jdk.GarbageCollection}'s duration is one full collection's
 * pause; {@code jdk.GCHeapSummary}'s {@code heapUsed} "After GC" is the
 * live-set size), never estimated from a wall-clock delta around the
 * workload call. This is a {@code main()}-based aux harness — JMH's
 * `-prof gc` reports allocation RATE well but has no percentile-pause
 * reporting across a whole run, which is this lab's actual subject.
 */
public final class GcTailHarness {

    private GcTailHarness() {}

    private static long percentile(long[] sorted, double frac) {
        if (sorted.length == 0) return 0;
        int idx = Math.min(sorted.length - 1, (int) (sorted.length * frac));
        return sorted[idx];
    }

    public static void main(String[] args) throws Exception {
        String variant = "steadyHighAllocation";
        String dataset = "objectGraphChurn";
        for (int i = 0; i < args.length - 1; i++) {
            if ("--variant".equals(args[i])) variant = args[i + 1];
            if ("--dataset".equals(args[i])) dataset = args[i + 1];
        }

        long[] values = GcTailFixtures.valuesFor(dataset, GcTailFixtures.N);
        int extraSize = GcTailFixtures.extraSizeFor(dataset);
        long expected = GcTailFixtures.expectedTotal(values);

        Path jfrOut = Files.createTempFile("gc-tail-latency-", ".jfr");
        Recording recording = new Recording();
        recording.enable("jdk.GarbageCollection");
        recording.enable("jdk.GCPhasePause");
        recording.enable("jdk.GCHeapSummary");
        recording.setDestination(jfrOut);
        recording.start();

        long actual = GcTailOperations.run(variant, values, extraSize);

        recording.stop();
        recording.close();

        if (actual != expected) {
            Files.deleteIfExists(jfrOut);
            throw new IllegalStateException(
                "correctness oracle failed for variant=" + variant + " dataset=" + dataset
                    + ": expected " + expected + " got " + actual);
        }

        List<Long> pauses = new ArrayList<>();
        Set<String> collectors = new LinkedHashSet<>();
        long maxLiveSet = 0;
        int gcCount = 0;
        for (RecordedEvent e : RecordingFile.readAllEvents(jfrOut)) {
            String type = e.getEventType().getName();
            if ("jdk.GarbageCollection".equals(type)) {
                pauses.add(e.getDuration().toNanos());
                collectors.add(e.getString("name"));
                gcCount++;
            } else if ("jdk.GCHeapSummary".equals(type) && "After GC".equals(e.getString("when"))) {
                maxLiveSet = Math.max(maxLiveSet, e.getLong("heapUsed"));
            }
        }
        Files.deleteIfExists(jfrOut);

        long[] sorted = pauses.stream().mapToLong(Long::longValue).sorted().toArray();

        System.out.printf(
            "{%n  \"harness\": \"GcTailHarness\",%n  \"variant\": \"%s\",%n  \"dataset\": \"%s\",%n"
                + "  \"checksum\": %d,%n  \"gcCount\": %d,%n  \"collectors\": %s,%n  \"liveSetBytes\": %d,%n"
                + "  \"pauseNanos\": { \"p50\": %d, \"p99\": %d, \"p999\": %d, \"max\": %d }%n}%n",
            variant, dataset, actual, gcCount, collectors, maxLiveSet,
            percentile(sorted, 0.50), percentile(sorted, 0.99), percentile(sorted, 0.999),
            sorted.length == 0 ? 0 : sorted[sorted.length - 1]);
    }
}
