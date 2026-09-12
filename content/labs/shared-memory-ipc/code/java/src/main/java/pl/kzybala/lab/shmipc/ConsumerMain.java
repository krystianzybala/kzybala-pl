package pl.kzybala.lab.shmipc;

import java.nio.file.Path;

/**
 * Real, standalone process entry point for the consumer side of the
 * cross-process correctness harness — launched as a genuinely separate OS
 * process, never as a thread. Usage:
 * {@code java -cp <classpath> pl.kzybala.lab.shmipc.ConsumerMain <segmentPath> <messageCount> <payloadSize> [startIndex]}.
 * Opens the existing segment (created by a producer process), consumes
 * exactly {@code messageCount} messages starting at logical index
 * {@code startIndex} (0 unless this is a resumed consumer in the
 * restart/recovery scenario), verifies each against the same deterministic
 * fixture the producer used, and prints {@code CONSUMER_OK <count>} on
 * success or {@code CONSUMER_FAIL <reason>} (with a non-zero exit) on any
 * mismatch.
 */
public final class ConsumerMain {

    private ConsumerMain() {
    }

    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]);
        long messageCount = Long.parseLong(args[1]);
        int payloadSize = Integer.parseInt(args[2]);
        long startIndex = args.length > 3 ? Long.parseLong(args[3]) : 0L;

        SharedRing ring = SharedRing.openExisting(path);
        ring.initReader();
        byte[] buf = new byte[SharedRing.PAYLOAD_CAPACITY];
        long received = 0;
        long expectedIndex = startIndex;
        while (received < messageCount) {
            int length = ring.tryConsume(buf);
            if (length < 0) {
                Thread.onSpinWait();
                continue;
            }
            byte[] expected = MessageFixtures.payload(expectedIndex, payloadSize);
            for (int i = 0; i < payloadSize; i++) {
                if (buf[i] != expected[i]) {
                    System.out.println("CONSUMER_FAIL mismatch-at-index-" + expectedIndex + "-byte-" + i);
                    System.exit(1);
                    return;
                }
            }
            expectedIndex++;
            received++;
        }
        System.out.println("CONSUMER_OK " + received);
    }
}
