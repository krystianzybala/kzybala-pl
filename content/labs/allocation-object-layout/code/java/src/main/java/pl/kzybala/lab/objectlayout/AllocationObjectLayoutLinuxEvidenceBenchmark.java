package pl.kzybala.lab.objectlayout;

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

import java.util.concurrent.TimeUnit;

/**
 * Publication-evidence benchmark for the native-Linux runner: one pinned
 * worker executing exactly one (variant, dataset) cell of the 4×3
 * matrix. Dataset generation and the correctness oracle (every variant
 * must reproduce the identical checksum for a given dataset) both run in
 * setup, never in the measured method. perf stat (cache-misses,
 * page-faults — see
 * scripts/performance-lab/labs/allocation-object-layout.conf) wraps this
 * process externally; this class contributes ns/op only. {@code construct}
 * and {@code sumHot} are separate operations — see
 * {@link AllocationObjectLayoutBenchmark}'s class doc for the contract.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class AllocationObjectLayoutLinuxEvidenceBenchmark {

    @Param({"boxedObjectGraph", "flatPrimitiveArrays", "packedOffHeapStruct", "reusedMutableHolder"})
    public String variant;

    @Param({"ordersQuotes", "treeNodes", "smallTuples"})
    public String dataset;

    private AllocationObjectLayoutFixtures.OrdersSource ordersSource;
    private long[] treeSource;
    private AllocationObjectLayoutFixtures.TuplesSource tuplesSource;

    private OrderRecord[] boxedOrders;
    private TreeNode boxedTreeRoot;
    private Tuple[] boxedTuples;

    private FlatPrimitiveArrays.Orders flatOrders;
    private long[] flatTree;
    private FlatPrimitiveArrays.Tuples flatTuples;

    private PackedOffHeapStruct.Orders packedOrders;
    private PackedOffHeapStruct.Tree packedTree;
    private PackedOffHeapStruct.Tuples packedTuples;

    private WorkerPin pin;

    @Setup(Level.Trial)
    public void setup() {
        if (WorkerPin.pinningRequested()) {
            pin = WorkerPin.establish("worker", WorkerPin.CPU_A);
        }

        long expected;
        long actual;
        switch (dataset) {
            case "ordersQuotes" -> {
                ordersSource = AllocationObjectLayoutFixtures.generateOrders(AllocationObjectLayoutFixtures.ORDERS_N);
                expected = AllocationObjectLayoutFixtures.expectedOrdersChecksum(ordersSource);
                actual = switch (variant) {
                    case "boxedObjectGraph" -> {
                        boxedOrders = BoxedObjectGraph.buildOrders(ordersSource);
                        yield BoxedObjectGraph.sumOrders(boxedOrders);
                    }
                    case "flatPrimitiveArrays" -> {
                        flatOrders = FlatPrimitiveArrays.buildOrders(ordersSource);
                        yield FlatPrimitiveArrays.sumOrders(flatOrders);
                    }
                    case "packedOffHeapStruct" -> {
                        packedOrders = PackedOffHeapStruct.buildOrders(ordersSource);
                        yield PackedOffHeapStruct.sumOrders(packedOrders);
                    }
                    case "reusedMutableHolder" -> ReusedMutableHolder.streamOrders(ordersSource);
                    default -> throw new IllegalStateException("unknown variant: " + variant);
                };
            }
            case "treeNodes" -> {
                treeSource = AllocationObjectLayoutFixtures.generateTreeValues(AllocationObjectLayoutFixtures.TREE_N);
                expected = AllocationObjectLayoutFixtures.expectedTreeChecksum(treeSource);
                actual = switch (variant) {
                    case "boxedObjectGraph" -> {
                        boxedTreeRoot = BoxedObjectGraph.buildTree(treeSource);
                        yield BoxedObjectGraph.sumTree(boxedTreeRoot);
                    }
                    case "flatPrimitiveArrays" -> {
                        flatTree = FlatPrimitiveArrays.buildTree(treeSource);
                        yield FlatPrimitiveArrays.sumTree(flatTree);
                    }
                    case "packedOffHeapStruct" -> {
                        packedTree = PackedOffHeapStruct.buildTree(treeSource);
                        yield PackedOffHeapStruct.sumTree(packedTree);
                    }
                    case "reusedMutableHolder" -> ReusedMutableHolder.streamTree(treeSource);
                    default -> throw new IllegalStateException("unknown variant: " + variant);
                };
            }
            case "smallTuples" -> {
                tuplesSource = AllocationObjectLayoutFixtures.generateTuples(AllocationObjectLayoutFixtures.TUPLES_N);
                expected = AllocationObjectLayoutFixtures.expectedTuplesChecksum(tuplesSource);
                actual = switch (variant) {
                    case "boxedObjectGraph" -> {
                        boxedTuples = BoxedObjectGraph.buildTuples(tuplesSource);
                        yield BoxedObjectGraph.sumTuples(boxedTuples);
                    }
                    case "flatPrimitiveArrays" -> {
                        flatTuples = FlatPrimitiveArrays.buildTuples(tuplesSource);
                        yield FlatPrimitiveArrays.sumTuples(flatTuples);
                    }
                    case "packedOffHeapStruct" -> {
                        packedTuples = PackedOffHeapStruct.buildTuples(tuplesSource);
                        yield PackedOffHeapStruct.sumTuples(packedTuples);
                    }
                    case "reusedMutableHolder" -> ReusedMutableHolder.streamTuples(tuplesSource);
                    default -> throw new IllegalStateException("unknown variant: " + variant);
                };
            }
            default -> throw new IllegalStateException("unknown dataset: " + dataset);
        }
        if (actual != expected) {
            throw new IllegalStateException(
                "correctness oracle failed for variant=" + variant + " dataset=" + dataset
                    + ": expected " + expected + " got " + actual);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (packedOrders != null) packedOrders.close();
        if (packedTree != null) packedTree.close();
        if (packedTuples != null) packedTuples.close();
        if (pin != null) pin.verifyAndRecord();
    }

    /** One operation = build a fresh representation from source and sum it (setup excluded). */
    @Benchmark
    public long construct() {
        return switch (dataset) {
            case "ordersQuotes" -> switch (variant) {
                case "boxedObjectGraph" -> BoxedObjectGraph.sumOrders(BoxedObjectGraph.buildOrders(ordersSource));
                case "flatPrimitiveArrays" ->
                    FlatPrimitiveArrays.sumOrders(FlatPrimitiveArrays.buildOrders(ordersSource));
                case "packedOffHeapStruct" -> {
                    try (PackedOffHeapStruct.Orders fresh = PackedOffHeapStruct.buildOrders(ordersSource)) {
                        yield PackedOffHeapStruct.sumOrders(fresh);
                    }
                }
                case "reusedMutableHolder" -> ReusedMutableHolder.streamOrders(ordersSource);
                default -> throw new IllegalStateException("unknown variant: " + variant);
            };
            case "treeNodes" -> switch (variant) {
                case "boxedObjectGraph" -> BoxedObjectGraph.sumTree(BoxedObjectGraph.buildTree(treeSource));
                case "flatPrimitiveArrays" -> FlatPrimitiveArrays.sumTree(FlatPrimitiveArrays.buildTree(treeSource));
                case "packedOffHeapStruct" -> {
                    try (PackedOffHeapStruct.Tree fresh = PackedOffHeapStruct.buildTree(treeSource)) {
                        yield PackedOffHeapStruct.sumTree(fresh);
                    }
                }
                case "reusedMutableHolder" -> ReusedMutableHolder.streamTree(treeSource);
                default -> throw new IllegalStateException("unknown variant: " + variant);
            };
            case "smallTuples" -> switch (variant) {
                case "boxedObjectGraph" -> BoxedObjectGraph.sumTuples(BoxedObjectGraph.buildTuples(tuplesSource));
                case "flatPrimitiveArrays" ->
                    FlatPrimitiveArrays.sumTuples(FlatPrimitiveArrays.buildTuples(tuplesSource));
                case "packedOffHeapStruct" -> {
                    try (PackedOffHeapStruct.Tuples fresh = PackedOffHeapStruct.buildTuples(tuplesSource)) {
                        yield PackedOffHeapStruct.sumTuples(fresh);
                    }
                }
                case "reusedMutableHolder" -> ReusedMutableHolder.streamTuples(tuplesSource);
                default -> throw new IllegalStateException("unknown variant: " + variant);
            };
            default -> throw new IllegalStateException("unknown dataset: " + dataset);
        };
    }

    /** One operation = read back the already-built, persistent representation (setup excluded). */
    @Benchmark
    public long sumHot() {
        return switch (dataset) {
            case "ordersQuotes" -> switch (variant) {
                case "boxedObjectGraph" -> BoxedObjectGraph.sumOrders(boxedOrders);
                case "flatPrimitiveArrays" -> FlatPrimitiveArrays.sumOrders(flatOrders);
                case "packedOffHeapStruct" -> PackedOffHeapStruct.sumOrders(packedOrders);
                case "reusedMutableHolder" -> ReusedMutableHolder.streamOrders(ordersSource);
                default -> throw new IllegalStateException("unknown variant: " + variant);
            };
            case "treeNodes" -> switch (variant) {
                case "boxedObjectGraph" -> BoxedObjectGraph.sumTree(boxedTreeRoot);
                case "flatPrimitiveArrays" -> FlatPrimitiveArrays.sumTree(flatTree);
                case "packedOffHeapStruct" -> PackedOffHeapStruct.sumTree(packedTree);
                case "reusedMutableHolder" -> ReusedMutableHolder.streamTree(treeSource);
                default -> throw new IllegalStateException("unknown variant: " + variant);
            };
            case "smallTuples" -> switch (variant) {
                case "boxedObjectGraph" -> BoxedObjectGraph.sumTuples(boxedTuples);
                case "flatPrimitiveArrays" -> FlatPrimitiveArrays.sumTuples(flatTuples);
                case "packedOffHeapStruct" -> PackedOffHeapStruct.sumTuples(packedTuples);
                case "reusedMutableHolder" -> ReusedMutableHolder.streamTuples(tuplesSource);
                default -> throw new IllegalStateException("unknown variant: " + variant);
            };
            default -> throw new IllegalStateException("unknown dataset: " + dataset);
        };
    }
}
