package pl.kzybala.lab.escapeanalysis;

/**
 * The small, two-field aggregate every variant constructs — deliberately
 * this simple (two {@code long} fields, no extra state) so that when it
 * does NOT get scalar-replaced, the reason is the variant's usage
 * pattern, never the shape of the object itself.
 */
public record Aggregate(long x, long y) {
    public long sum() {
        return x + y;
    }
}
