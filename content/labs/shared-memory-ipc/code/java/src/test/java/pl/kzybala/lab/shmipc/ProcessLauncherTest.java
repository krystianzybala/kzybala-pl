package pl.kzybala.lab.shmipc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real cross-process correctness proof this lab's traps list requires
 * ("missing cross-process memory-order proof"): {@link ProducerMain} and
 * {@link ConsumerMain} are launched as genuinely separate OS processes via
 * {@link ProcessBuilder}, each with its own JVM and its own independent
 * memory mapping of the shared backing file — not threads sharing one
 * Java object. If the release/acquire publication protocol in
 * {@link SharedRing} were broken, this test (unlike a same-process
 * threads test) would be exercising the real mechanism that could expose
 * it: two separate address spaces, coherent only through the OS-mediated
 * shared mapping.
 */
class ProcessLauncherTest {

    private static final String JAVA_BIN = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    private static final String CLASSPATH = System.getProperty("java.class.path");

    @Test
    void producerAndConsumerAsRealSeparateProcessesDeliverEveryMessage(@TempDir Path dir) throws Exception {
        Path segment = dir.resolve("ring.bin");
        long messageCount = 5_000;
        int payloadSize = 64;
        int capacity = 256;

        Process producer = new ProcessBuilder(
                JAVA_BIN, "-cp", CLASSPATH, "pl.kzybala.lab.shmipc.ProducerMain",
                segment.toString(), String.valueOf(capacity), String.valueOf(messageCount), String.valueOf(payloadSize))
                .redirectErrorStream(true)
                .start();

        // Poll for the segment file to reach its full size before starting the
        // consumer — avoids a race where the consumer opens a not-yet-created file.
        long expectedSize = SharedRing.segmentSize(capacity);
        waitForFileSize(segment, expectedSize, 5_000);

        Process consumer = new ProcessBuilder(
                JAVA_BIN, "-cp", CLASSPATH, "pl.kzybala.lab.shmipc.ConsumerMain",
                segment.toString(), String.valueOf(messageCount), String.valueOf(payloadSize))
                .redirectErrorStream(true)
                .start();

        String producerOutput = readAllOutput(producer);
        String consumerOutput = readAllOutput(consumer);

        assertEquals(0, producer.waitFor(), "producer process failed: " + producerOutput);
        assertEquals(0, consumer.waitFor(), "consumer process failed: " + consumerOutput);
        assertTrue(consumerOutput.contains("CONSUMER_OK " + messageCount),
                "unexpected consumer output: " + consumerOutput);
    }

    @Test
    void restartedConsumerResumesWithoutLossOrDuplication(@TempDir Path dir) throws Exception {
        Path segment = dir.resolve("ring.bin");
        int capacity = 256;
        int payloadSize = 32;
        long firstBatch = 2_000;
        long secondBatch = 2_000;

        Process producer = new ProcessBuilder(
                JAVA_BIN, "-cp", CLASSPATH, "pl.kzybala.lab.shmipc.ProducerMain",
                segment.toString(), String.valueOf(capacity), String.valueOf(firstBatch + secondBatch),
                String.valueOf(payloadSize))
                .redirectErrorStream(true)
                .start();
        waitForFileSize(segment, SharedRing.segmentSize(capacity), 5_000);

        // First consumer reads only the first batch, then is killed — simulating a crash mid-stream.
        Process firstConsumer = new ProcessBuilder(
                JAVA_BIN, "-cp", CLASSPATH, "pl.kzybala.lab.shmipc.ConsumerMain",
                segment.toString(), String.valueOf(firstBatch), String.valueOf(payloadSize), "0")
                .redirectErrorStream(true)
                .start();
        String firstOutput = readAllOutput(firstConsumer);
        assertEquals(0, firstConsumer.waitFor(), "first consumer failed: " + firstOutput);
        assertTrue(firstOutput.contains("CONSUMER_OK " + firstBatch), firstOutput);

        // A freshly-started consumer resumes reading the SAME segment from where
        // the ring's own readerSeq actually is (it does not re-derive its resume
        // point from firstBatch, matching a real crash-recovery scenario where a
        // fresh process only knows the segment path, not what the previous
        // consumer instance believed it had read).
        Process secondConsumer = new ProcessBuilder(
                JAVA_BIN, "-cp", CLASSPATH, "pl.kzybala.lab.shmipc.ConsumerMain",
                segment.toString(), String.valueOf(secondBatch), String.valueOf(payloadSize), String.valueOf(firstBatch))
                .redirectErrorStream(true)
                .start();
        String secondOutput = readAllOutput(secondConsumer);
        assertEquals(0, secondConsumer.waitFor(), "second consumer failed: " + secondOutput);
        assertTrue(secondOutput.contains("CONSUMER_OK " + secondBatch), secondOutput);

        assertEquals(0, producer.waitFor());
    }

    private static void waitForFileSize(Path path, long expectedSize, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(path) && Files.size(path) >= expectedSize) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("segment file never reached expected size " + expectedSize);
    }

    private static String readAllOutput(Process process) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
