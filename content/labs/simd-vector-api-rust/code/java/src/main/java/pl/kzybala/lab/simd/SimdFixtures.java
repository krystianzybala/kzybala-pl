package pl.kzybala.lab.simd;

/**
 * Deterministic source-data generation for the four datasets — the
 * cross-language equivalence contract
 * (../fixtures/simd-vector-api-rust-fixtures.json). Every variant reads
 * the identical values and reproduces the identical result;
 * vectorization strategy changes ns/element and vector instructions,
 * never the result. {@code dotProduct}'s values are always small
 * integers exactly representable as {@code double}, specifically so
 * summation order (scalar sequential vs. vector-lane reduction) never
 * changes the bit-exact result — a genuine floating-point semantics
 * decision, not an oversight (theory.md).
 */
public final class SimdFixtures {

    public static final int SUM_MIN_MAX_N = 1_000_000;
    public static final int THRESHOLD_N = 1_000_000;
    public static final int THRESHOLD = 500_000;
    public static final int DOT_PRODUCT_N = 1_000_000;
    public static final int BYTE_CLASSIFICATION_N = 2_000_000;
    public static final int SMALL_TAIL_N = 17;

    private SimdFixtures() {}

    public static long xorshift64(long x) {
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        return x;
    }

    public static int[] generateSumMinMax(int n) {
        int[] value = new int[n];
        long x = 120L;
        for (int i = 0; i < n; i++) {
            x = xorshift64(x);
            value[i] = (int) Long.remainderUnsigned(x, 1_000_000);
        }
        return value;
    }

    public static int[] generateThreshold(int n) {
        int[] value = new int[n];
        long x = 121L;
        for (int i = 0; i < n; i++) {
            x = xorshift64(x);
            value[i] = (int) Long.remainderUnsigned(x, 1_000_000);
        }
        return value;
    }

    public static final class DotProductSource {
        public final double[] a;
        public final double[] b;

        DotProductSource(double[] a, double[] b) {
            this.a = a;
            this.b = b;
        }
    }

    public static DotProductSource generateDotProduct(int n) {
        double[] a = new double[n];
        double[] b = new double[n];
        long x = 122L;
        for (int i = 0; i < n; i++) {
            x = xorshift64(x);
            a[i] = Long.remainderUnsigned(x, 1_000);
        }
        long y = 123L;
        for (int i = 0; i < n; i++) {
            y = xorshift64(y);
            b[i] = Long.remainderUnsigned(y, 1_000);
        }
        return new DotProductSource(a, b);
    }

    public static byte[] generateByteClassification(int n) {
        byte[] value = new byte[n];
        long x = 124L;
        for (int i = 0; i < n; i++) {
            x = xorshift64(x);
            value[i] = (byte) Long.remainderUnsigned(x, 256);
        }
        return value;
    }
}
