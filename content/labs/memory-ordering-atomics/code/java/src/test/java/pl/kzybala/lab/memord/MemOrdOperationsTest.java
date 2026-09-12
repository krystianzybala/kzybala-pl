package pl.kzybala.lab.memord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemOrdOperationsTest {

    // ---- single-slot mailbox ----
    // "Correct" variants must show zero forbidden reads AND zero
    // timeouts across every trial — a timeout would itself indicate a
    // real hang risk in the mechanism, not just an inconclusive sample.

    @Test
    void mailboxAcquireReleaseNeverObservesStaleRead() throws InterruptedException {
        assertAllCorrect(MailboxKernel::acquireReleasePublication, MemOrdFixtures.MAILBOX_TRIALS);
    }

    @Test
    void mailboxVolatileSeqCstNeverObservesStaleRead() throws InterruptedException {
        assertAllCorrect(MailboxKernel::volatileSeqCstPublication, MemOrdFixtures.MAILBOX_TRIALS);
    }

    @Test
    void mailboxCasLoopNeverObservesStaleRead() throws InterruptedException {
        assertAllCorrect(MailboxKernel::casLoop, MemOrdFixtures.MAILBOX_TRIALS);
    }

    @Test
    void mailboxFenceBasedNeverObservesStaleRead() throws InterruptedException {
        assertAllCorrect(MailboxKernel::fenceBased, MemOrdFixtures.MAILBOX_TRIALS);
    }

    /** NOT a correctness assertion — a demonstration run. The plain
     * variant is never gated on producing (or not producing) the
     * forbidden outcome; only that it completes without hanging or
     * throwing. Whatever forbidden-read rate is observed here is
     * reported honestly in this lab's java.md, never asserted. */
    @Test
    void mailboxPlainBrokenPublicationCompletesWithoutHanging() throws InterruptedException {
        int forbidden = 0;
        int timedOut = 0;
        int trials = Math.min(MemOrdFixtures.MAILBOX_TRIALS, 500);
        for (int i = 0; i < trials; i++) {
            MailboxKernel.Outcome outcome = MailboxKernel.plainBrokenPublication();
            if (outcome == MailboxKernel.Outcome.FORBIDDEN_STALE_READ) forbidden++;
            if (outcome == MailboxKernel.Outcome.TIMED_OUT) timedOut++;
        }
        assertTrue(timedOut == 0, "plain publication should not hang the reader (bounded spin) — saw " + timedOut + " timeouts");
        System.out.println("mailbox plainBrokenPublication: " + forbidden + "/" + trials + " forbidden reads observed (not asserted, reported honestly)");
    }

    private interface MailboxRun {
        MailboxKernel.Outcome run() throws InterruptedException;
    }

    private static void assertAllCorrect(MailboxRun run, int trials) throws InterruptedException {
        for (int i = 0; i < trials; i++) {
            MailboxKernel.Outcome outcome = run.run();
            assertEquals(MailboxKernel.Outcome.CORRECT, outcome, "trial " + i + " did not observe the exact published payload");
        }
    }

    // ---- sequence flag plus payload ----

    @Test
    void seqFlagAcquireReleaseNeverObservesStalePayload() throws InterruptedException {
        assertAllCorrectSeq(SequenceFlagKernel::acquireReleasePublication, MemOrdFixtures.SEQFLAG_TRIALS);
    }

    @Test
    void seqFlagVolatileSeqCstNeverObservesStalePayload() throws InterruptedException {
        assertAllCorrectSeq(SequenceFlagKernel::volatileSeqCstPublication, MemOrdFixtures.SEQFLAG_TRIALS);
    }

    @Test
    void seqFlagCasLoopNeverObservesStalePayload() throws InterruptedException {
        assertAllCorrectSeq(SequenceFlagKernel::casLoop, MemOrdFixtures.SEQFLAG_TRIALS);
    }

    @Test
    void seqFlagFenceBasedNeverObservesStalePayload() throws InterruptedException {
        assertAllCorrectSeq(SequenceFlagKernel::fenceBased, MemOrdFixtures.SEQFLAG_TRIALS);
    }

    @Test
    void seqFlagPlainBrokenPublicationCompletesWithoutHanging() throws InterruptedException {
        int forbidden = 0;
        int timedOut = 0;
        int trials = Math.min(MemOrdFixtures.SEQFLAG_TRIALS, 20);
        for (int i = 0; i < trials; i++) {
            SequenceFlagKernel.Outcome outcome = SequenceFlagKernel.plainBrokenPublication();
            if (outcome == SequenceFlagKernel.Outcome.FORBIDDEN_STALE_PAYLOAD) forbidden++;
            if (outcome == SequenceFlagKernel.Outcome.TIMED_OUT) timedOut++;
        }
        assertTrue(timedOut == 0, "plain publication should not hang the reader (bounded spin) — saw " + timedOut + " timeouts");
        System.out.println("seqFlag plainBrokenPublication: " + forbidden + "/" + trials + " forbidden reads observed (not asserted, reported honestly)");
    }

    private interface SeqFlagRun {
        SequenceFlagKernel.Outcome run() throws InterruptedException;
    }

    private static void assertAllCorrectSeq(SeqFlagRun run, int trials) throws InterruptedException {
        for (int i = 0; i < trials; i++) {
            SequenceFlagKernel.Outcome outcome = run.run();
            assertEquals(SequenceFlagKernel.Outcome.CORRECT, outcome, "trial " + i + " observed a stale payload behind its own sequence number");
        }
    }

    // ---- counter update ----

    @Test
    void counterAcquireReleaseMatchesExpectedTotalExactly() throws InterruptedException {
        CounterUpdateKernel.Result r = CounterUpdateKernel.acquireReleasePublication();
        assertEquals(MemOrdFixtures.COUNTER_EXPECTED_TOTAL, r.total);
    }

    @Test
    void counterCasLoopMatchesExpectedTotalExactly() throws InterruptedException {
        CounterUpdateKernel.Result r = CounterUpdateKernel.casLoop();
        assertEquals(MemOrdFixtures.COUNTER_EXPECTED_TOTAL, r.total);
    }

    @Test
    void counterFenceBasedMatchesExpectedTotalExactly() throws InterruptedException {
        CounterUpdateKernel.Result r = CounterUpdateKernel.fenceBased();
        assertEquals(MemOrdFixtures.COUNTER_EXPECTED_TOTAL, r.total);
    }

    /** The trap variant — deliberately NOT asserted to equal the
     * expected total. A seq-cst FIELD accessed via separate load/store
     * is not an atomic RMW; lost updates under contention are the
     * expected, real result, reported honestly rather than gated on. */
    @Test
    void counterVolatileSeqCstCompletesAndReportsObservedLoss() throws InterruptedException {
        CounterUpdateKernel.Result r = CounterUpdateKernel.volatileSeqCstPublication();
        assertTrue(r.total <= MemOrdFixtures.COUNTER_EXPECTED_TOTAL, "total should never exceed the expected count");
        System.out.println("counter volatileSeqCstPublication: total=" + r.total + " expected=" + MemOrdFixtures.COUNTER_EXPECTED_TOTAL + " (not asserted equal — reported honestly)");
    }

    @Test
    void counterPlainBrokenPublicationCompletesAndReportsObservedLoss() throws InterruptedException {
        CounterUpdateKernel.Result r = CounterUpdateKernel.plainBrokenPublication();
        assertTrue(r.total <= MemOrdFixtures.COUNTER_EXPECTED_TOTAL, "total should never exceed the expected count");
        System.out.println("counter plainBrokenPublication: total=" + r.total + " expected=" + MemOrdFixtures.COUNTER_EXPECTED_TOTAL + " (not asserted equal — reported honestly)");
    }
}
