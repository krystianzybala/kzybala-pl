package pl.kzybala.lab.mmap;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark comparing buffered read, mmap sequential (warm and freshly
 * mapped), mmap random access, and mapped write+flush over the fixed
 * 64 MiB / 1,048,576-record dataset (the same size used by the
 * "sixtyFourMebibyteFile" fixture profile). Every {@code @Benchmark} does
 * one full pass over the file per invocation, matching the "one benchmark
 * operation = one full scenario pass" contract in benchmark.md.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
public class MmapBenchmark {

    private static final long RECORD_COUNT = 1_048_576;
    private static final long FILE_SIZE = RECORD_COUNT * RecordFile.RECORD_SIZE;

    private Path readFile;
    private Path writeFile;
    private int[] randomOrder;
    private MappedByteBuffer warmMapped;

    private final BufferedReadKernel bufferedKernel = new BufferedReadKernel();
    private final MmapReadKernel mmapReadKernel = new MmapReadKernel();
    private final MmapWriteFlushKernel writeKernel = new MmapWriteFlushKernel();

    @Setup(Level.Trial)
    public void setUpTrial() throws IOException {
        readFile = Files.createTempFile("mmap-lab-read-", ".bin");
        RecordFile.write(readFile, RECORD_COUNT);
        randomOrder = MmapReadKernel.deterministicPermutation((int) RECORD_COUNT, 424242L);
        // Warm the mapping once here (untimed) so warmSequentialRead measures
        // an already-resident mapping, distinct from coldMappedSequentialRead's
        // freshly-created mapping per invocation.
        warmMapped = mmapReadKernel.mapFreshly(readFile, FILE_SIZE);
        mmapReadKernel.readSequentialChecksum(warmMapped, RECORD_COUNT);
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() throws IOException {
        Files.deleteIfExists(readFile);
    }

    @Benchmark
    public void bufferedRead(Blackhole bh) throws IOException {
        bh.consume(bufferedKernel.readAllChecksum(readFile, RECORD_COUNT));
    }

    @Benchmark
    public void warmMmapSequentialRead(Blackhole bh) {
        bh.consume(mmapReadKernel.readSequentialChecksum(warmMapped, RECORD_COUNT));
    }

    /**
     * Maps the same file freshly on every invocation. This does not force a
     * true OS-page-cache-cold read on a dev machine (see theory.md's
     * Assumptions and scope) — it isolates the cost of establishing a new
     * mapping and the first traversal through it, which is the honestly
     * nameable state this benchmark can produce without root.
     */
    @Benchmark
    public void coldMappedSequentialRead(Blackhole bh) throws IOException {
        MappedByteBuffer mapped = mmapReadKernel.mapFreshly(readFile, FILE_SIZE);
        bh.consume(mmapReadKernel.readSequentialChecksum(mapped, RECORD_COUNT));
    }

    @Benchmark
    public void randomMappedAccess(Blackhole bh) {
        bh.consume(mmapReadKernel.readRandomChecksum(warmMapped, randomOrder));
    }

    @Benchmark
    public void mappedWriteAndFlush(Blackhole bh) throws IOException {
        Path file = Files.createTempFile("mmap-lab-write-", ".bin");
        try {
            MappedByteBuffer mapped = writeKernel.mapForWrite(file, FILE_SIZE);
            writeKernel.writeAll(mapped, RECORD_COUNT);
            writeKernel.flush(mapped);
            bh.consume(mapped.get(0));
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
