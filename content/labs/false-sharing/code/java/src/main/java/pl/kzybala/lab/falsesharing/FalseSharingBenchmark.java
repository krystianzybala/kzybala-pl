package pl.kzybala.lab.falsesharing;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * Companion benchmark for the "False sharing" Performance Lab
 * (kzybala.pl/lab/false-sharing/). {@code @Group}/{@code Scope.Group} is
 * what makes JMH run the two counter writes concurrently on separate
 * threads within one group — without it, this would never reproduce the
 * cross-core invalidation traffic being measured. See benchmark.md for
 * environment and methodology disclosure.
 */
@State(Scope.Group)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = {
    "-XX:-RestrictContended",
    "--add-exports", "java.base/jdk.internal.vm.annotation=ALL-UNNAMED",
})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class FalseSharingBenchmark {

    private final SharedCounters shared = new SharedCounters();
    private final PaddedCounters padded = new PaddedCounters();
    private final ContendedCounters contended = new ContendedCounters();

    @Benchmark
    @Group("shared")
    public void writeA_shared() { shared.counterA++; }

    @Benchmark
    @Group("shared")
    public void writeB_shared() { shared.counterB++; }

    @Benchmark
    @Group("padded")
    public void writeA_padded() { padded.counterA++; }

    @Benchmark
    @Group("padded")
    public void writeB_padded() { padded.counterB++; }

    @Benchmark
    @Group("contended")
    public void writeA_contended() { contended.counterA++; }

    @Benchmark
    @Group("contended")
    public void writeB_contended() { contended.counterB++; }
}
