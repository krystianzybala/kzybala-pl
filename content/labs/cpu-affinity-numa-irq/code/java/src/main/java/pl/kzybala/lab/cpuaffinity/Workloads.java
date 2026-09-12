package pl.kzybala.lab.cpuaffinity;

/**
 * Pure, deterministic checksum functions for the three datasets. Each is independent of thread
 * placement — placement can change how fast these run (the mechanism this lab measures), never
 * whether they produce the correct answer, which is what makes them safe correctness fixtures.
 */
public final class Workloads {
    private Workloads() {}

    /** Models N sequential handoffs between a producer and consumer as N deterministic updates. */
    public static long spscHandoffChecksum(int n) {
        long x = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < n; i++) {
            x ^= (x + i) << 13;
            x ^= x >>> 7;
            x ^= x << 17;
        }
        return x;
    }

    /** Sums a deterministically-initialized array, scanned with the given stride (1 = sequential). */
    public static long memoryScanChecksum(int size, int stride) {
        long[] data = new long[size];
        for (int i = 0; i < size; i++) {
            data[i] = (i * 2654435761L) & 0xFFFFFFFFL;
        }
        long sum = 0;
        for (int i = 0; i < size; i += stride) {
            sum += data[i];
        }
        return sum;
    }

    /** Checksums packetCount synthetic packets of packetSize bytes each, deterministically generated. */
    public static long udpIngestChecksum(int packetCount, int packetSize) {
        long sum = 0;
        for (int p = 0; p < packetCount; p++) {
            long packetSeed = (p * 0x2545F4914F6CDD1DL) + 1;
            for (int b = 0; b < packetSize; b++) {
                byte value = (byte) ((packetSeed >>> (b % 56)) ^ (p + b));
                sum += value;
            }
        }
        return sum;
    }
}
