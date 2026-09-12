package pl.kzybala.lab.structlayout;

import org.openjdk.jol.info.ClassLayout;

/**
 * Real per-instance heap layout evidence for the five
 * {@code producerConsumerCounters} variants, via JOL
 * (org.openjdk.jol) — this is captured evidence (it introspects the
 * actual running JVM's object layout), not an estimate, and confirms
 * (or corrects) this lab's hand-computed padding for {@link
 * ProducerConsumerCounters.PoorFieldOrder}/{@link
 * ProducerConsumerCounters.CacheLineAligned}. Only the throughput
 * numbers in benchmark.md are restricted to the native-Linux evidence
 * runner.
 */
public final class JolReport {

    private JolReport() {}

    public static void main(String[] args) {
        report("Natural", new ProducerConsumerCounters.Natural());
        report("PoorFieldOrder", new ProducerConsumerCounters.PoorFieldOrder());
        report("OptimizedFieldOrder", new ProducerConsumerCounters.OptimizedFieldOrder());
        report("CacheLineAligned", new ProducerConsumerCounters.CacheLineAligned());
        report("PackedUnaligned", new ProducerConsumerCounters.PackedUnaligned());
    }

    private static void report(String name, Object instance) {
        System.out.println("=== producerConsumerCounters." + name + " (org.openjdk.jol.info.ClassLayout) ===");
        System.out.println(ClassLayout.parseInstance(instance).toPrintable());
    }
}
