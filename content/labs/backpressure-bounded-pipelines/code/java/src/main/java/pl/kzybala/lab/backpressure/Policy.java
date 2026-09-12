package pl.kzybala.lab.backpressure;

public enum Policy {
    UNBOUNDED,
    BOUNDED_REJECT,
    BOUNDED_BLOCK,
    DROP_OLDEST,
    COALESCE_BY_KEY,
    LOAD_SHEDDING
}
