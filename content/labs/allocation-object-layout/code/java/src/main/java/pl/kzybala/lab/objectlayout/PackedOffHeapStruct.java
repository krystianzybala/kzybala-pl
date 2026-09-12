package pl.kzybala.lab.objectlayout;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Variant 3: packed off-heap struct. One contiguous {@link MemorySegment}
 * holds every record back-to-back with no object header, no reference
 * indirection and no GC involvement at all. Every field keeps its own
 * natural alignment (8-byte fields at 8-byte-aligned offsets, 4-byte
 * fields at 4-byte-aligned offsets) — this lab's "using packed unaligned
 * fields unsafely" trap names exactly the mistake this layout avoids:
 * fields are dense (no wasted padding) because they are ordered
 * widest-first, never forced unaligned to save a few more bytes.
 * Per-record byte offsets are computed once from a {@link MemoryLayout}
 * and accessed via plain {@code segment.get/set(ValueLayout, long
 * offset)} calls, not layout-path {@code VarHandle}s — see
 * content/labs/aos-vs-soa's java.md for the real ~100x C2 regression that
 * pattern caused when this repository first built a packed layout.
 */
public final class PackedOffHeapStruct {

    private PackedOffHeapStruct() {}

    // ---- ordersQuotes: id(8) + priceTicks(8) + quantity(4) + flags(4) = 24 bytes/record ----

    public static final class Orders implements AutoCloseable {
        private static final MemoryLayout LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("id"),
            ValueLayout.JAVA_LONG.withName("priceTicks"),
            ValueLayout.JAVA_INT.withName("quantity"),
            ValueLayout.JAVA_INT.withName("flags"));
        private static final long STRIDE = LAYOUT.byteSize();
        private static final long ID_OFF = LAYOUT.byteOffset(PathElement.groupElement("id"));
        private static final long PRICE_OFF = LAYOUT.byteOffset(PathElement.groupElement("priceTicks"));
        private static final long QTY_OFF = LAYOUT.byteOffset(PathElement.groupElement("quantity"));
        private static final long FLAGS_OFF = LAYOUT.byteOffset(PathElement.groupElement("flags"));

        private final Arena arena;
        private final MemorySegment segment;
        private final int n;

        private Orders(Arena arena, MemorySegment segment, int n) {
            this.arena = arena;
            this.segment = segment;
            this.n = n;
        }

        static Orders build(AllocationObjectLayoutFixtures.OrdersSource s) {
            int n = s.id.length;
            Arena arena = Arena.ofShared();
            MemorySegment segment = arena.allocate(STRIDE * n, LAYOUT.byteAlignment());
            for (int i = 0; i < n; i++) {
                long base = i * STRIDE;
                segment.set(ValueLayout.JAVA_LONG, base + ID_OFF, s.id[i]);
                segment.set(ValueLayout.JAVA_LONG, base + PRICE_OFF, s.priceTicks[i]);
                segment.set(ValueLayout.JAVA_INT, base + QTY_OFF, s.quantity[i]);
                segment.set(ValueLayout.JAVA_INT, base + FLAGS_OFF, s.flags[i]);
            }
            return new Orders(arena, segment, n);
        }

        long sum() {
            long sum = 0;
            long base = 0;
            for (int i = 0; i < n; i++, base += STRIDE) {
                sum += segment.get(ValueLayout.JAVA_LONG, base + ID_OFF)
                    + segment.get(ValueLayout.JAVA_LONG, base + PRICE_OFF)
                    + segment.get(ValueLayout.JAVA_INT, base + QTY_OFF)
                    + segment.get(ValueLayout.JAVA_INT, base + FLAGS_OFF);
            }
            return sum;
        }

        public long recordStrideBytes() {
            return STRIDE;
        }

        @Override
        public void close() {
            arena.close();
        }
    }

    public static Orders buildOrders(AllocationObjectLayoutFixtures.OrdersSource s) {
        return Orders.build(s);
    }

    public static long sumOrders(Orders o) {
        return o.sum();
    }

    // ---- treeNodes: value(8) = 8 bytes/record, structure index-implicit ----

    public static final class Tree implements AutoCloseable {
        private final Arena arena;
        private final MemorySegment segment;
        private final int n;

        private Tree(Arena arena, MemorySegment segment, int n) {
            this.arena = arena;
            this.segment = segment;
            this.n = n;
        }

        static Tree build(long[] value) {
            int n = value.length;
            Arena arena = Arena.ofShared();
            MemorySegment segment = arena.allocate(8L * n, 8);
            for (int i = 0; i < n; i++) {
                segment.set(ValueLayout.JAVA_LONG, i * 8L, value[i]);
            }
            return new Tree(arena, segment, n);
        }

        /** Index-arithmetic tree traversal over the packed segment — a real traversal, not a linear scan. */
        long sum() {
            long sum = 0;
            int[] stack = new int[64];
            int sp = 0;
            stack[sp++] = 0;
            while (sp > 0) {
                int i = stack[--sp];
                if (i < 0 || i >= n) continue;
                sum += segment.get(ValueLayout.JAVA_LONG, i * 8L);
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

        @Override
        public void close() {
            arena.close();
        }
    }

    public static Tree buildTree(long[] value) {
        return Tree.build(value);
    }

    public static long sumTree(Tree t) {
        return t.sum();
    }

    // ---- smallTuples: x(4) + y(4) = 8 bytes/record ----

    public static final class Tuples implements AutoCloseable {
        private final Arena arena;
        private final MemorySegment segment;
        private final int n;

        private Tuples(Arena arena, MemorySegment segment, int n) {
            this.arena = arena;
            this.segment = segment;
            this.n = n;
        }

        static Tuples build(AllocationObjectLayoutFixtures.TuplesSource s) {
            int n = s.x.length;
            Arena arena = Arena.ofShared();
            MemorySegment segment = arena.allocate(8L * n, 4);
            for (int i = 0; i < n; i++) {
                long base = i * 8L;
                segment.set(ValueLayout.JAVA_INT, base, s.x[i]);
                segment.set(ValueLayout.JAVA_INT, base + 4, s.y[i]);
            }
            return new Tuples(arena, segment, n);
        }

        long sum() {
            long sum = 0;
            long base = 0;
            for (int i = 0; i < n; i++, base += 8L) {
                sum += segment.get(ValueLayout.JAVA_INT, base) + segment.get(ValueLayout.JAVA_INT, base + 4);
            }
            return sum;
        }

        @Override
        public void close() {
            arena.close();
        }
    }

    public static Tuples buildTuples(AllocationObjectLayoutFixtures.TuplesSource s) {
        return Tuples.build(s);
    }

    public static long sumTuples(Tuples t) {
        return t.sum();
    }
}
