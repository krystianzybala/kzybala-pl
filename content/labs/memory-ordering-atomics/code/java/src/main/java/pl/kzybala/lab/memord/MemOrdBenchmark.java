package pl.kzybala.lab.memord;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * Companion benchmark for the "Memory Ordering: VarHandles and Rust
 * Atomics" Performance Lab (kzybala.pl/lab/memory-ordering-atomics/).
 *
 * <p>Each {@code @Benchmark} method invokes one dataset/variant kernel
 * method directly. Because every kernel spawns and joins its own worker
 * thread(s) internally (the mailbox and sequence-flag protocols are
 * inherently two-thread; the counter-update protocol is inherently
 * {@code COUNTER_THREAD_COUNT}-thread), every measured operation here
 * includes real thread spawn/join overhead — this is a macro benchmark of
 * the full protocol, not a steady-state microbenchmark of one isolated
 * operation. See benchmark.md for why this makes the numbers here
 * non-comparable to a hypothetical persistent-thread variant, and for
 * the exact reproduction commands.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class MemOrdBenchmark {

    // ---- single-slot mailbox ----

    @Benchmark
    public MailboxKernel.Outcome mailbox_acquireRelease() throws InterruptedException {
        return MailboxKernel.acquireReleasePublication();
    }

    @Benchmark
    public MailboxKernel.Outcome mailbox_volatileSeqCst() throws InterruptedException {
        return MailboxKernel.volatileSeqCstPublication();
    }

    @Benchmark
    public MailboxKernel.Outcome mailbox_casLoop() throws InterruptedException {
        return MailboxKernel.casLoop();
    }

    @Benchmark
    public MailboxKernel.Outcome mailbox_fenceBased() throws InterruptedException {
        return MailboxKernel.fenceBased();
    }

    // ---- sequence flag plus payload ----
    // Note: each invocation performs SEQFLAG_UPDATE_COUNT sequential
    // updates, not a single operation — see benchmark.md's operation
    // definition for this dataset.

    @Benchmark
    public SequenceFlagKernel.Outcome seqFlag_acquireRelease() throws InterruptedException {
        return SequenceFlagKernel.acquireReleasePublication();
    }

    @Benchmark
    public SequenceFlagKernel.Outcome seqFlag_volatileSeqCst() throws InterruptedException {
        return SequenceFlagKernel.volatileSeqCstPublication();
    }

    @Benchmark
    public SequenceFlagKernel.Outcome seqFlag_casLoop() throws InterruptedException {
        return SequenceFlagKernel.casLoop();
    }

    @Benchmark
    public SequenceFlagKernel.Outcome seqFlag_fenceBased() throws InterruptedException {
        return SequenceFlagKernel.fenceBased();
    }

    // ---- counter update ----
    // Note: each invocation spawns COUNTER_THREAD_COUNT threads performing
    // COUNTER_INCREMENTS_PER_THREAD increments each — see benchmark.md.

    @Benchmark
    public CounterUpdateKernel.Result counter_acquireRelease() throws InterruptedException {
        return CounterUpdateKernel.acquireReleasePublication();
    }

    @Benchmark
    public CounterUpdateKernel.Result counter_casLoop() throws InterruptedException {
        return CounterUpdateKernel.casLoop();
    }

    @Benchmark
    public CounterUpdateKernel.Result counter_fenceBased() throws InterruptedException {
        return CounterUpdateKernel.fenceBased();
    }
}
