package pl.kzybala.lab.objectlayout;

/**
 * Variant 4: reused mutable holder. ONE instance is allocated per pass and
 * its fields are overwritten from the source arrays record by record —
 * construction cost is amortized across the entire dataset rather than
 * paid per record, at the cost of never materializing more than one
 * record at a time. This variant has no separate "build once, read many"
 * phase the way the other three do: streaming through the source IS the
 * operation, which is a genuine, honest design trade-off (java.md), not a
 * simplification of what the other variants measure.
 */
public final class ReusedMutableHolder {

    private ReusedMutableHolder() {}

    public static long streamOrders(AllocationObjectLayoutFixtures.OrdersSource s) {
        OrderRecord holder = new OrderRecord(0, 0, 0, 0);
        long sum = 0;
        int n = s.id.length;
        for (int i = 0; i < n; i++) {
            holder.id = s.id[i];
            holder.priceTicks = s.priceTicks[i];
            holder.quantity = s.quantity[i];
            holder.flags = s.flags[i];
            sum += holder.id + holder.priceTicks + holder.quantity + holder.flags;
        }
        return sum;
    }

    public static long streamTree(long[] value) {
        TreeNode holder = new TreeNode(0);
        long sum = 0;
        for (long v : value) {
            holder.value = v;
            sum += holder.value;
        }
        return sum;
    }

    public static long streamTuples(AllocationObjectLayoutFixtures.TuplesSource s) {
        Tuple holder = new Tuple(0, 0);
        long sum = 0;
        int n = s.x.length;
        for (int i = 0; i < n; i++) {
            holder.x = s.x[i];
            holder.y = s.y[i];
            sum += holder.x + holder.y;
        }
        return sum;
    }
}
