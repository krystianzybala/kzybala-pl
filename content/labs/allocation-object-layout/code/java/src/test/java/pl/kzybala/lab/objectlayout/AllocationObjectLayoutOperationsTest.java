package pl.kzybala.lab.objectlayout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Correctness gate against the shared fixture
 * (../fixtures/allocation-object-layout-fixtures.json). All four
 * representation variants must sum to the identical total for a given
 * dataset — representation changes bytes/instance and construction cost,
 * never the result.
 */
class AllocationObjectLayoutOperationsTest {

    @Test
    void ordersQuotesAllVariantsAgree() {
        AllocationObjectLayoutFixtures.OrdersSource s =
            AllocationObjectLayoutFixtures.generateOrders(AllocationObjectLayoutFixtures.ORDERS_N);
        assertEquals(0L, s.id[0]);
        assertEquals(7834695L, s.priceTicks[0]);
        assertEquals(5558, s.quantity[0]);
        assertEquals(6, s.flags[0]);

        long expected = AllocationObjectLayoutFixtures.expectedOrdersChecksum(s);
        assertEquals(1_022_629_873_049L, expected);

        assertEquals(expected, BoxedObjectGraph.sumOrders(BoxedObjectGraph.buildOrders(s)));
        assertEquals(expected, FlatPrimitiveArrays.sumOrders(FlatPrimitiveArrays.buildOrders(s)));
        try (PackedOffHeapStruct.Orders packed = PackedOffHeapStruct.buildOrders(s)) {
            assertEquals(expected, PackedOffHeapStruct.sumOrders(packed));
        }
        assertEquals(expected, ReusedMutableHolder.streamOrders(s));
    }

    @Test
    void treeNodesAllVariantsAgree() {
        long[] value = AllocationObjectLayoutFixtures.generateTreeValues(AllocationObjectLayoutFixtures.TREE_N);
        assertEquals(327111L, value[0]);
        assertEquals(663972L, value[1]);

        long expected = AllocationObjectLayoutFixtures.expectedTreeChecksum(value);
        assertEquals(100_091_314_403L, expected);

        assertEquals(expected, BoxedObjectGraph.sumTree(BoxedObjectGraph.buildTree(value)));
        assertEquals(expected, FlatPrimitiveArrays.sumTree(FlatPrimitiveArrays.buildTree(value)));
        try (PackedOffHeapStruct.Tree packed = PackedOffHeapStruct.buildTree(value)) {
            assertEquals(expected, PackedOffHeapStruct.sumTree(packed));
        }
        assertEquals(expected, ReusedMutableHolder.streamTree(value));
    }

    @Test
    void smallTuplesAllVariantsAgree() {
        AllocationObjectLayoutFixtures.TuplesSource s =
            AllocationObjectLayoutFixtures.generateTuples(AllocationObjectLayoutFixtures.TUPLES_N);
        assertEquals(22792, s.x[0]);
        assertEquals(27340, s.y[0]);

        long expected = AllocationObjectLayoutFixtures.expectedTuplesChecksum(s);
        assertEquals(99_994_993_638L, expected);

        assertEquals(expected, BoxedObjectGraph.sumTuples(BoxedObjectGraph.buildTuples(s)));
        assertEquals(expected, FlatPrimitiveArrays.sumTuples(FlatPrimitiveArrays.buildTuples(s)));
        try (PackedOffHeapStruct.Tuples packed = PackedOffHeapStruct.buildTuples(s)) {
            assertEquals(expected, PackedOffHeapStruct.sumTuples(packed));
        }
        assertEquals(expected, ReusedMutableHolder.streamTuples(s));
    }
}
