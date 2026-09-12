package pl.kzybala.lab.coload;

/** Hardcoded mirror of code/fixtures/coordinated-omission-load-generation-fixtures.json. */
public final class CoLoadFixtures {
    private CoLoadFixtures() {}

    public static LoadParams periodicTenMsStall() {
        return new LoadParams(600, ServiceTimeModel.PERIODIC_STALL, 1_000_000L, 1000, 5);
    }

    public static LoadParams gcPauseInjection() {
        return new LoadParams(600, ServiceTimeModel.GC_PAUSE_INJECTION, 1_000_000L, 1000, 5);
    }

    public static LoadParams boundedServerOverload() {
        return new LoadParams(600, ServiceTimeModel.BOUNDED_OVERLOAD, 100L, 20, 5);
    }
}
