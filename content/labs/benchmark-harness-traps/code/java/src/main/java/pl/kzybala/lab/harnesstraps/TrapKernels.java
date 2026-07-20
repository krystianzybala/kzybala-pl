package pl.kzybala.lab.harnesstraps;

/**
 * The four kernels measured by this lab, defined once and shared by every
 * benchmark variant — the TRAP variants and the CORRECTED variants call
 * exactly the same code, so any difference in reported cost comes from the
 * measurement harness, never from the work itself.
 *
 * Semantics are the cross-language equivalence contract
 * (code/fixtures/benchmark-harness-traps-fixtures.json): all arithmetic is
 * 64-bit wrapping (Java long overflow == Rust wrapping_*), shifts on the
 * xorshift are logical, and the Rust implementations must produce the
 * identical fixture values.
 */
public final class TrapKernels {

    private TrapKernels() {}

    /** Repository-canonical xorshift64 step (same as the other labs). */
    public static long xorshift64(long x) {
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        return x;
    }

    /** Tiny scalar operation: {@code rounds} xorshift64 steps from {@code seed}. */
    public static long mixScalar(long seed, int rounds) {
        long x = seed;
        for (int i = 0; i < rounds; i++) {
            x = xorshift64(x);
        }
        return x;
    }

    /** Deterministic dataset: the xorshift64 stream from {@code seed}. */
    public static long[] fillArray(long seed, int length) {
        long[] out = new long[length];
        long x = seed;
        for (int i = 0; i < length; i++) {
            x = xorshift64(x);
            out[i] = x;
        }
        return out;
    }

    /** Array reduction: wrapping 64-bit sum. */
    public static long reduce(long[] values) {
        long sum = 0;
        for (long v : values) {
            sum += v;
        }
        return sum;
    }

    /**
     * Deterministic parser input: {@code count} decimal values
     * (stream value mod 1_000_000), comma-joined. Built once in setup —
     * the parser benchmarks reuse this input, they never rebuild it in the
     * measured operation.
     */
    public static String buildParserInput(long seed, int count) {
        StringBuilder sb = new StringBuilder(count * 7);
        long x = seed;
        for (int i = 0; i < count; i++) {
            x = xorshift64(x);
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Long.remainderUnsigned(x, 1_000_000L));
        }
        return sb.toString();
    }

    /**
     * Parses the comma-separated decimal input and folds it into a wrapping
     * checksum ({@code checksum*31 + value}); verifies the value count so a
     * short or corrupted parse cannot silently pass.
     */
    public static long parseChecksum(String input, int expectedCount) {
        long checksum = 0;
        int count = 0;
        int i = 0;
        int n = input.length();
        while (i < n) {
            long value = 0;
            while (i < n && input.charAt(i) != ',') {
                value = value * 10 + (input.charAt(i) - '0');
                i++;
            }
            checksum = checksum * 31 + value;
            count++;
            i++; // skip the comma (or step past the end)
        }
        if (count != expectedCount) {
            throw new IllegalStateException(
                "parsed " + count + " values, expected " + expectedCount);
        }
        return checksum;
    }

    /**
     * Stateful counter — the state-leakage dataset. Correct benchmarks
     * reset it per iteration (or declare the leakage); the trap is letting
     * one invocation's state silently change the next invocation's work.
     */
    public static final class StatefulCounter {
        private long state;

        public StatefulCounter(long seed) {
            this.state = seed;
        }

        /** One wrapping LCG step; returns the new state. */
        public long advance() {
            state = state * 6364136223846793005L + 1442695040888963407L;
            return state;
        }

        public long state() {
            return state;
        }

        public void reset(long seed) {
            this.state = seed;
        }
    }

    /** {@code steps} counter advances from {@code seed} — the fixture oracle. */
    public static long counterAfter(long seed, int steps) {
        StatefulCounter counter = new StatefulCounter(seed);
        long last = seed;
        for (int i = 0; i < steps; i++) {
            last = counter.advance();
        }
        return last;
    }
}
