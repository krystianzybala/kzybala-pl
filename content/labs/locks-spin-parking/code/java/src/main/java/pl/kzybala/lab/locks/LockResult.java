package pl.kzybala.lab.locks;

/** Common result shape for every wait-strategy variant. */
public final class LockResult {
    public final long finalCounter;
    public final long casFailures;
    public final long parkCount;

    public LockResult(long finalCounter, long casFailures, long parkCount) {
        this.finalCounter = finalCounter;
        this.casFailures = casFailures;
        this.parkCount = parkCount;
    }

    public boolean isCorrect(int workerCount, int opsPerWorker) {
        return finalCounter == (long) workerCount * opsPerWorker;
    }
}
