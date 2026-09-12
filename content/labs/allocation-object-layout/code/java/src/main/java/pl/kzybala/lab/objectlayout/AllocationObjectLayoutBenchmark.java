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
 * Companion dev/wiring benchmark for the "Allocation cost and object
 * layout" Performance Lab (kzybala.pl/lab/allocation-object-layout/): the
 * four representation variants over the {@code ordersQuotes} dataset
 * only, unpinned — for local smoke and IDE profiling. Publication
 * evidence comes exclusively from
 * {@link AllocationObjectLayoutLinuxEvidenceBenchmark} via the
 * native-Linux runner (see benchmark.md).
 *
 * <p>Two benchmark methods per variant, both required by this lab's
 * metrics: {@code construct} builds a fresh representation from the
 * source arrays EVERY invocation (this IS the timed construction cost —
 * the source, "hot" data used every time this method runs is prepared
 * once in {@code @Setup(Trial)}), while {@code sumHot} reads back one
 * ALREADY-BUILT, persistent representation (built once in setup). For
 * {@code reusedMutableHolder} the two methods call the identical
 * underlying stream — there is no separate "build once, read many" phase
 * for this variant (java.md).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class AllocationObjectLayoutBenchmark {

    @Param({"boxedObjectGraph", "flatPrimitiveArrays", "packedOffHeapStruct", "reusedMutableHolder"})
    public String variant;

    private AllocationObjectLayoutFixtures.OrdersSource source;

    private OrderRecord[] boxedPersistent;
    private FlatPrimitiveArrays.Orders flatPersistent;
    private PackedOffHeapStruct.Orders packedPersistent;

    @Setup(Level.Trial)
    public void setup() {
        source = AllocationObjectLayoutFixtures.generateOrders(AllocationObjectLayoutFixtures.ORDERS_N);
        switch (variant) {
            case "boxedObjectGraph" -> boxedPersistent = BoxedObjectGraph.buildOrders(source);
            case "flatPrimitiveArrays" -> flatPersistent = FlatPrimitiveArrays.buildOrders(source);
            case "packedOffHeapStruct" -> packedPersistent = PackedOffHeapStruct.buildOrders(source);
            case "reusedMutableHolder" -> { /* no persistent structure — see class doc */ }
            default -> throw new IllegalStateException("unknown variant: " + variant);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (packedPersistent != null) packedPersistent.close();
    }

    /** One operation = build a fresh representation from source and sum it. */
    @Benchmark
    public long construct() {
        return switch (variant) {
            case "boxedObjectGraph" -> BoxedObjectGraph.sumOrders(BoxedObjectGraph.buildOrders(source));
            case "flatPrimitiveArrays" -> FlatPrimitiveArrays.sumOrders(FlatPrimitiveArrays.buildOrders(source));
            case "packedOffHeapStruct" -> {
                try (PackedOffHeapStruct.Orders fresh = PackedOffHeapStruct.buildOrders(source)) {
                    yield PackedOffHeapStruct.sumOrders(fresh);
                }
            }
            case "reusedMutableHolder" -> ReusedMutableHolder.streamOrders(source);
            default -> throw new IllegalStateException("unknown variant: " + variant);
        };
    }

    /** One operation = read back the already-built, persistent representation. */
    @Benchmark
    public long sumHot() {
        return switch (variant) {
            case "boxedObjectGraph" -> BoxedObjectGraph.sumOrders(boxedPersistent);
            case "flatPrimitiveArrays" -> FlatPrimitiveArrays.sumOrders(flatPersistent);
            case "packedOffHeapStruct" -> PackedOffHeapStruct.sumOrders(packedPersistent);
            case "reusedMutableHolder" -> ReusedMutableHolder.streamOrders(source);
            default -> throw new IllegalStateException("unknown variant: " + variant);
        };
    }
}
