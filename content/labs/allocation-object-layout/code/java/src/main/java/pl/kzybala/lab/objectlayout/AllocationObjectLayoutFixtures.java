package pl.kzybala.lab.objectlayout;

/**
 * Deterministic source-data generation for the three datasets — the
 * cross-language equivalence contract
 * (../fixtures/allocation-object-layout-fixtures.json). Every
 * representation variant reads the identical field values for a given
 * dataset; representation changes bytes/instance and construction cost,
 * never the checksum.
 */
public final class AllocationObjectLayoutFixtures {

    public static final int ORDERS_N = 200_000;
    public static final int TREE_N = 200_000;
    public static final int TUPLES_N = 1_000_000;

    private AllocationObjectLayoutFixtures() {}

    public static long xorshift64(long x) {
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        return x;
    }

    /** Source arrays for ordersQuotes: id (linear), priceTicks, quantity, flags. */
    public static final class OrdersSource {
        public final long[] id;
        public final long[] priceTicks;
        public final int[] quantity;
        public final int[] flags;

        OrdersSource(long[] id, long[] priceTicks, int[] quantity, int[] flags) {
            this.id = id;
            this.priceTicks = priceTicks;
            this.quantity = quantity;
            this.flags = flags;
        }
    }

    public static OrdersSource generateOrders(int n) {
        long[] id = new long[n];
        long[] priceTicks = new long[n];
        int[] quantity = new int[n];
        int[] flags = new int[n];
        long x = 70L;
        for (int i = 0; i < n; i++) {
            id[i] = i;
            x = xorshift64(x);
            priceTicks[i] = Long.remainderUnsigned(x, 10_000_000) + 1;
            x = xorshift64(x);
            quantity[i] = (int) Long.remainderUnsigned(x, 10_000) + 1;
            x = xorshift64(x);
            flags[i] = (int) Long.remainderUnsigned(x, 16);
        }
        return new OrdersSource(id, priceTicks, quantity, flags);
    }

    public static long expectedOrdersChecksum(OrdersSource s) {
        long sum = 0;
        for (int i = 0; i < s.id.length; i++) {
            sum += s.id[i] + s.priceTicks[i] + s.quantity[i] + s.flags[i];
        }
        return sum;
    }

    /** Source array for treeNodes: value only — structure is index-implicit (child(i) = 2i+1, 2i+2). */
    public static long[] generateTreeValues(int n) {
        long[] value = new long[n];
        long x = 71L;
        for (int i = 0; i < n; i++) {
            x = xorshift64(x);
            value[i] = Long.remainderUnsigned(x, 1_000_000);
        }
        return value;
    }

    public static long expectedTreeChecksum(long[] value) {
        long sum = 0;
        for (long v : value) sum += v;
        return sum;
    }

    /** Source arrays for smallTuples: x, y. */
    public static final class TuplesSource {
        public final int[] x;
        public final int[] y;

        TuplesSource(int[] x, int[] y) {
            this.x = x;
            this.y = y;
        }
    }

    public static TuplesSource generateTuples(int n) {
        int[] x = new int[n];
        int[] y = new int[n];
        long s = 72L;
        for (int i = 0; i < n; i++) {
            s = xorshift64(s);
            x[i] = (int) Long.remainderUnsigned(s, 100_000);
            s = xorshift64(s);
            y[i] = (int) Long.remainderUnsigned(s, 100_000);
        }
        return new TuplesSource(x, y);
    }

    public static long expectedTuplesChecksum(TuplesSource s) {
        long sum = 0;
        for (int i = 0; i < s.x.length; i++) sum += s.x[i] + s.y[i];
        return sum;
    }
}
