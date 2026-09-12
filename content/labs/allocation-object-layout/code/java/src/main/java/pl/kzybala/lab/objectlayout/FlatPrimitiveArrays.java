package pl.kzybala.lab.objectlayout;

/**
 * Variant 2: flat primitive arrays (struct-of-arrays). No per-record
 * object header at all — every field lives in its own dense primitive
 * array, and a record is identified only by its index. For
 * {@code treeNodes}, the tree's structure is index-implicit (node i's
 * children live at {@code 2i+1}/{@code 2i+2}, the classic complete-
 * binary-tree-as-array technique) — a genuine, real-world representation
 * choice, not a simplification of the boxed graph's semantics: traversal
 * still walks parent-to-child via index arithmetic rather than reading
 * every value in index order, so it remains a real tree traversal.
 * {@code build*} methods always allocate and copy fresh arrays (never
 * return the fixture's own arrays by reference) so construction cost is
 * measured fairly against the other three variants.
 */
public final class FlatPrimitiveArrays {

    private FlatPrimitiveArrays() {}

    // ---- ordersQuotes ----

    public static final class Orders {
        public final long[] id;
        public final long[] priceTicks;
        public final int[] quantity;
        public final int[] flags;

        Orders(long[] id, long[] priceTicks, int[] quantity, int[] flags) {
            this.id = id;
            this.priceTicks = priceTicks;
            this.quantity = quantity;
            this.flags = flags;
        }
    }

    public static Orders buildOrders(AllocationObjectLayoutFixtures.OrdersSource s) {
        return new Orders(s.id.clone(), s.priceTicks.clone(), s.quantity.clone(), s.flags.clone());
    }

    public static long sumOrders(Orders o) {
        long sum = 0;
        for (int i = 0; i < o.id.length; i++) {
            sum += o.id[i] + o.priceTicks[i] + o.quantity[i] + o.flags[i];
        }
        return sum;
    }

    // ---- treeNodes ----

    public static long[] buildTree(long[] value) {
        return value.clone();
    }

    /** Index-arithmetic tree traversal — a real traversal, not a linear scan. */
    public static long sumTree(long[] value) {
        int n = value.length;
        long sum = 0;
        int[] stack = new int[64];
        int sp = 0;
        stack[sp++] = 0;
        while (sp > 0) {
            int i = stack[--sp];
            if (i < 0 || i >= n) continue;
            sum += value[i];
            if (sp + 2 >= stack.length) {
                int[] grown = new int[stack.length * 2];
                System.arraycopy(stack, 0, grown, 0, sp);
                stack = grown;
            }
            stack[sp++] = 2 * i + 1;
            stack[sp++] = 2 * i + 2;
        }
        return sum;
    }

    // ---- smallTuples ----

    public static final class Tuples {
        public final int[] x;
        public final int[] y;

        Tuples(int[] x, int[] y) {
            this.x = x;
            this.y = y;
        }
    }

    public static Tuples buildTuples(AllocationObjectLayoutFixtures.TuplesSource s) {
        return new Tuples(s.x.clone(), s.y.clone());
    }

    public static long sumTuples(Tuples t) {
        long sum = 0;
        for (int i = 0; i < t.x.length; i++) sum += t.x[i] + t.y[i];
        return sum;
    }
}
