package pl.kzybala.lab.binser;

/**
 * Hard-coded mirror of {@code code/fixtures/fixed-binary-serialization-fixtures.json}.
 * Kept in source (not read from the JSON at runtime) so the correctness
 * suite has no I/O dependency and so any accidental drift between the JSON
 * and either language's actual asserted values is caught by comparing both
 * languages' test output, not hidden behind a shared loader.
 */
public final class BinSerFixtures {

    private BinSerFixtures() {
    }

    public static EventRecord smallCommand() {
        return new EventRecord((byte) 1, 42L, 0, (byte) 7, (byte) 1, 0.0, 0L,
                0, new int[EventRecord.MAX_SAMPLES], false, 0);
    }

    public static EventRecord mediumEvent() {
        return new EventRecord((byte) 1, 1000000007L, 314159, (byte) 2, (byte) 0, 98.6, 1731000000000L,
                0, new int[EventRecord.MAX_SAMPLES], false, 0);
    }

    public static EventRecord repeatedFields() {
        return new EventRecord((byte) 1, 99L, 5, (byte) 3, (byte) 0, 0.0, 0L,
                8, new int[]{10, -20, 30, -40, 50, -60, 70, -80}, false, 0);
    }

    public static EventRecord versionedOptionalField() {
        return new EventRecord((byte) 2, 555L, 8, (byte) 9, (byte) 4, 0.0, 0L,
                0, new int[EventRecord.MAX_SAMPLES], true, 123456);
    }

    public static EventRecord[] all() {
        return new EventRecord[]{smallCommand(), mediumEvent(), repeatedFields(), versionedOptionalField()};
    }
}
