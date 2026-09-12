package pl.kzybala.lab.dllp;

/**
 * The pipeline's "decode + decide" step, expressed as a pure function of (key, eventId,
 * eventSizeBytes) — deliberately never materializing a real fixed-size byte array in the
 * correctness fixture (that belongs to the real zero-copy frame-view code in java.md/rust.md and
 * the benchmark harness). Purity here is what lets NAIVE and OPTIMIZED assert the identical
 * checksum despite radically different queueing/allocation strategies around this call.
 */
public final class Decision {
    private Decision() {}

    public static long decisionChecksum(int key, int eventId, int eventSizeBytes) {
        long x = ((long) key << 32) ^ ((long) eventId * 0x9E3779B97F4A7C15L) ^ eventSizeBytes;
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        return x;
    }
}
