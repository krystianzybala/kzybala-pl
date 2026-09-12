package pl.kzybala.lab.shmipc;

import java.nio.file.Path;

/**
 * Real, standalone process entry point for the producer side of the
 * cross-process correctness harness — launched as a genuinely separate OS
 * process (see {@code ProcessLauncherTest}), never as a thread. Usage:
 * {@code java -cp <classpath> pl.kzybala.lab.shmipc.ProducerMain <segmentPath> <capacity> <messageCount> <payloadSize> [resume] [startIndex]}.
 * By default creates a fresh segment; pass {@code resume} as the fifth
 * argument (with the message index to resume from as the sixth) to open
 * an existing segment instead — the restart/recovery scenario's producer
 * side. Publishes {@code messageCount} deterministic messages starting at
 * {@code startIndex}, then exits 0. Any exception exits non-zero.
 */
public final class ProducerMain {

    private ProducerMain() {
    }

    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]);
        int capacity = Integer.parseInt(args[1]);
        long messageCount = Long.parseLong(args[2]);
        int payloadSize = Integer.parseInt(args[3]);
        boolean resume = args.length > 4 && "resume".equals(args[4]);
        long startIndex = args.length > 5 ? Long.parseLong(args[5]) : 0L;

        SharedRing ring;
        if (resume) {
            ring = SharedRing.openExisting(path);
            ring.resumeWriter();
        } else {
            ring = SharedRing.createNew(path, capacity);
            ring.initWriter();
        }
        for (long i = startIndex; i < startIndex + messageCount; i++) {
            byte[] payload = MessageFixtures.payload(i, payloadSize);
            while (!ring.tryPublish(payload, payloadSize)) {
                Thread.onSpinWait();
            }
        }
        System.out.println("PRODUCER_DONE " + messageCount);
    }
}
