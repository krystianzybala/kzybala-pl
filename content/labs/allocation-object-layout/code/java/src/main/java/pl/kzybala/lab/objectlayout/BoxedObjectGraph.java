package pl.kzybala.lab.objectlayout;

/**
 * Variant 1: boxed object graph. Every logical record is its own heap
 * object — {@code OrderRecord}/{@code TreeNode}/{@code Tuple} instances,
 * each carrying a real object header (mark word + klass pointer, plus a
 * length word for arrays) on top of its logical fields. For
 * {@code treeNodes} this is a genuine linked graph: nodes are connected
 * via real {@code left}/{@code right} object references, summed by
 * pointer-chasing traversal, not index arithmetic — the representation
 * this lab's "comparing object graph with reduced functionality" trap
 * warns against replacing with something that only LOOKS equivalent.
 */
public final class BoxedObjectGraph {

    private BoxedObjectGraph() {}

    // ---- ordersQuotes ----

    public static OrderRecord[] buildOrders(AllocationObjectLayoutFixtures.OrdersSource s) {
        int n = s.id.length;
        OrderRecord[] records = new OrderRecord[n];
        for (int i = 0; i < n; i++) {
            records[i] = new OrderRecord(s.id[i], s.priceTicks[i], s.quantity[i], s.flags[i]);
        }
        return records;
    }

    public static long sumOrders(OrderRecord[] records) {
        long sum = 0;
        for (OrderRecord r : records) {
            sum += r.id + r.priceTicks + r.quantity + r.flags;
        }
        return sum;
    }

    // ---- treeNodes ----

    /** Builds a complete binary tree of n nodes with real left/right references; returns the root. */
    public static TreeNode buildTree(long[] value) {
        int n = value.length;
        TreeNode[] nodes = new TreeNode[n];
        for (int i = 0; i < n; i++) {
            nodes[i] = new TreeNode(value[i]);
        }
        for (int i = 0; i < n; i++) {
            int leftIdx = 2 * i + 1;
            int rightIdx = 2 * i + 2;
            if (leftIdx < n) nodes[i].left = nodes[leftIdx];
            if (rightIdx < n) nodes[i].right = nodes[rightIdx];
        }
        return nodes[0];
    }

    /** Iterative (non-recursive, stack-safe for deep trees) pointer-chasing traversal. */
    public static long sumTree(TreeNode root) {
        long sum = 0;
        TreeNode[] stack = new TreeNode[64];
        int sp = 0;
        stack[sp++] = root;
        while (sp > 0) {
            TreeNode node = stack[--sp];
            if (node == null) continue;
            sum += node.value;
            if (sp + 2 >= stack.length) {
                TreeNode[] grown = new TreeNode[stack.length * 2];
                System.arraycopy(stack, 0, grown, 0, sp);
                stack = grown;
            }
            stack[sp++] = node.left;
            stack[sp++] = node.right;
        }
        return sum;
    }

    // ---- smallTuples ----

    public static Tuple[] buildTuples(AllocationObjectLayoutFixtures.TuplesSource s) {
        int n = s.x.length;
        Tuple[] tuples = new Tuple[n];
        for (int i = 0; i < n; i++) {
            tuples[i] = new Tuple(s.x[i], s.y[i]);
        }
        return tuples;
    }

    public static long sumTuples(Tuple[] tuples) {
        long sum = 0;
        for (Tuple t : tuples) sum += t.x + t.y;
        return sum;
    }
}
