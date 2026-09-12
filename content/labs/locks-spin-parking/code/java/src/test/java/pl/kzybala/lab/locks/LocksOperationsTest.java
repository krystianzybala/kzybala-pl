package pl.kzybala.lab.locks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LocksOperationsTest {

    @Test
    void uncontendedMutexReachesExactCount() throws InterruptedException {
        LockResult r = MutexKernel.uncontended();
        assertTrue(r.isCorrect(1, LocksFixtures.OPS_PER_WORKER));
    }

    @Test
    void shortContendedMutexReachesExactCount() throws InterruptedException {
        LockResult r = MutexKernel.shortContended(LocksFixtures.WORKER_COUNT, LocksFixtures.OPS_PER_WORKER);
        assertTrue(r.isCorrect(LocksFixtures.WORKER_COUNT, LocksFixtures.OPS_PER_WORKER));
    }

    @Test
    void longCriticalSectionReachesExactCount() throws InterruptedException {
        LockResult r = MutexKernel.longCriticalSection(
                LocksFixtures.WORKER_COUNT, LocksFixtures.OPS_PER_WORKER, LocksFixtures.LONG_CS_BUSY_ITERATIONS);
        assertTrue(r.isCorrect(LocksFixtures.WORKER_COUNT, LocksFixtures.OPS_PER_WORKER));
    }

    @Test
    void casLoopReachesExactCount() throws InterruptedException {
        LockResult r = CasLoopKernel.run(LocksFixtures.WORKER_COUNT, LocksFixtures.OPS_PER_WORKER);
        assertTrue(r.isCorrect(LocksFixtures.WORKER_COUNT, LocksFixtures.OPS_PER_WORKER));
    }

    @Test
    void spinThenParkReachesExactCount() throws InterruptedException {
        LockResult r = SpinThenParkKernel.run(
                LocksFixtures.WORKER_COUNT, LocksFixtures.OPS_PER_WORKER, LocksFixtures.SPIN_LIMIT);
        assertTrue(r.isCorrect(LocksFixtures.WORKER_COUNT, LocksFixtures.OPS_PER_WORKER));
    }

    /** With a spin limit of 0, every acquisition attempt that finds the
     * lock held tries to park immediately — correctness must still hold
     * exactly. Whether a park actually happens (versus the double-check
     * re-CAS winning first) is a genuine timing race, not something this
     * test can force deterministically, so the park count is reported,
     * not asserted — the same "observed, not gated" treatment this lab's
     * other genuinely racy outcomes get elsewhere in this repository. */
    @Test
    void spinThenParkWithZeroSpinLimitStillReachesExactCount() throws InterruptedException {
        LockResult r = SpinThenParkKernel.run(LocksFixtures.WORKER_COUNT, LocksFixtures.OPS_PER_WORKER, 0);
        assertTrue(r.isCorrect(LocksFixtures.WORKER_COUNT, LocksFixtures.OPS_PER_WORKER));
        System.out.println("spinThenPark with spinLimit=0: parkCount=" + r.parkCount + " (not asserted — timing-dependent)");
    }
}
