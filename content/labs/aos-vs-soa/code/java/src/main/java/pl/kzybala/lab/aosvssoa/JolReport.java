package pl.kzybala.lab.aosvssoa;

import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.info.GraphLayout;

/**
 * Real per-instance and per-graph heap layout evidence for the AoS heap
 * objects variant, via JOL (org.openjdk.jol) — this is captured evidence
 * (it introspects the actual running JVM's object layout), not an
 * estimate, and runs on any JVM including a development machine; only the
 * throughput numbers in benchmark.md are restricted to the native-Linux
 * evidence runner.
 */
public final class JolReport {

    private JolReport() {}

    public static void main(String[] args) {
        AosVsSoaFixtures.Generated g = AosVsSoaFixtures.generate(4, AosVsSoaFixtures.SEED, 1_000);
        AosHeapLayout.Record[] records = AosHeapLayout.of(g).records();

        System.out.println("=== Single-instance layout (org.openjdk.jol.info.ClassLayout) ===");
        System.out.println(ClassLayout.parseInstance(records[0]).toPrintable());

        System.out.println("=== Whole-graph footprint for " + records.length + " records (org.openjdk.jol.info.GraphLayout) ===");
        GraphLayout graph = GraphLayout.parseInstance((Object) records); // one root: the array itself
        System.out.println(graph.toFootprint());
        System.out.println("totalBytes=" + graph.totalSize()
            + " bytesPerRecord=" + (graph.totalSize() / (double) records.length));
    }
}
