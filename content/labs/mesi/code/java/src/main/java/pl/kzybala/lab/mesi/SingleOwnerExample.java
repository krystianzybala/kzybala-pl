package pl.kzybala.lab.mesi;

/**
 * Companion code for the "Cache coherence and MESI" Performance Lab
 * (kzybala.pl/lab/mesi/). Not synchronized, and correct anyway: exactly one
 * thread ever touches {@code total} for the object's whole lifetime, so
 * there is no other core to invalidate this line — the software analogue
 * of the lab's Exclusive/Modified single-owner path. See java.md.
 */
public class SingleOwnerExample {

    private long total;

    public void addFrom(int iterations) {
        for (int i = 0; i < iterations; i++) total += i;
    }

    public long total() {
        return total;
    }
}
