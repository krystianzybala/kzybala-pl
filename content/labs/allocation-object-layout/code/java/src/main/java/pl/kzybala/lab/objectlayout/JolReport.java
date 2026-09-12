package pl.kzybala.lab.objectlayout;

import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.info.GraphLayout;

/**
 * Real per-instance and per-graph heap layout evidence for the boxed
 * object graph variant, via JOL (org.openjdk.jol) — this is captured
 * evidence (it introspects the actual running JVM's object layout), not
 * an estimate, and runs on any JVM including a development machine; only
 * the throughput numbers in benchmark.md are restricted to the
 * native-Linux evidence runner.
 */
public final class JolReport {

    private static final int SAMPLE_N = 1_000;

    private JolReport() {}

    public static void main(String[] args) {
        AllocationObjectLayoutFixtures.OrdersSource orders = AllocationObjectLayoutFixtures.generateOrders(SAMPLE_N);
        OrderRecord[] orderRecords = BoxedObjectGraph.buildOrders(orders);
        report("ordersQuotes", orderRecords[0], orderRecords, orderRecords.length,
            PackedOffHeapStruct.buildOrders(orders).recordStrideBytes());

        long[] treeValues = AllocationObjectLayoutFixtures.generateTreeValues(SAMPLE_N);
        TreeNode treeRoot = BoxedObjectGraph.buildTree(treeValues);
        report("treeNodes", treeRoot, treeRoot, SAMPLE_N, 8L);

        AllocationObjectLayoutFixtures.TuplesSource tuples = AllocationObjectLayoutFixtures.generateTuples(SAMPLE_N);
        Tuple[] tupleRecords = BoxedObjectGraph.buildTuples(tuples);
        report("smallTuples", tupleRecords[0], tupleRecords, tupleRecords.length, 8L);
    }

    private static void report(String dataset, Object oneInstance, Object graphRoot, int n, long packedStrideBytes) {
        System.out.println("=== " + dataset + ": single-instance layout (org.openjdk.jol.info.ClassLayout) ===");
        System.out.println(ClassLayout.parseInstance(oneInstance).toPrintable());

        GraphLayout graph = GraphLayout.parseInstance(graphRoot);
        System.out.println("=== " + dataset + ": whole-graph footprint for " + n + " records (org.openjdk.jol.info.GraphLayout) ===");
        System.out.println(graph.toFootprint());
        double bytesPerRecordBoxed = graph.totalSize() / (double) n;
        System.out.println("boxedObjectGraph: totalBytes=" + graph.totalSize()
            + " bytesPerRecord=" + bytesPerRecordBoxed);
        System.out.println("packedOffHeapStruct: bytesPerRecord=" + packedStrideBytes
            + " (no header, no reference indirection)");
        System.out.printf("headerOverheadRatio (boxed / packed) = %.2fx%n%n",
            bytesPerRecordBoxed / packedStrideBytes);
    }
}
