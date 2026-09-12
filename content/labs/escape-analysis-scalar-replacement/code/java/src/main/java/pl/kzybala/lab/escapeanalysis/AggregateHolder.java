package pl.kzybala.lab.escapeanalysis;

/**
 * A field an {@link Aggregate} can be stored into — the "stored into
 * field" variant's escape path. Storing a reference into any
 * longer-lived container (an instance field, here) is GlobalEscape from
 * C2's point of view: the object must be materialized because something
 * outside the current method can now observe it.
 */
public final class AggregateHolder {
    private Aggregate last;

    public void store(Aggregate a) {
        this.last = a;
    }

    public Aggregate last() {
        return last;
    }
}
