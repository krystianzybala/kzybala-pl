package pl.kzybala.lab.observability;

import java.util.stream.IntStream;

/** Hardcoded mirror of code/fixtures/observability-overhead-fixtures.json. */
public final class ObsFixtures {
    private ObsFixtures() {}

    private static int[] sequential(int n) {
        return IntStream.range(0, n).toArray();
    }

    public static ObsParams singleEvent() {
        return new ObsParams(sequential(1), 4, 8, 16, 3);
    }

    public static ObsParams errorBurst() {
        return new ObsParams(sequential(2000), 4, 8, 16, 3);
    }

    public static ObsParams highCardinalityKey() {
        return new ObsParams(sequential(2000), 500, 8, 16, 3);
    }

    public static ObsParams stackTracePath() {
        return new ObsParams(sequential(2000), 4, 8, 16, 12);
    }
}
