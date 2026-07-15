package pl.kzybala.lab.falsesharing;

import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.info.FieldLayout;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the memory layouts the benchmarks claim, instead of assuming
 * them: field offsets are read from the running JVM with JOL. If a JVM
 * update reorders fields or changes padding behavior, this test fails
 * before a benchmark ever publishes a number based on the stale layout
 * assumption.
 *
 * <p>Assumed line size is 64 bytes (documented assumption, see theory.md).
 */
class CounterLayoutTest {

    private static final long ASSUMED_CACHE_LINE_BYTES = 64;

    private static Map<String, Long> fieldOffsets(Class<?> cls) {
        Map<String, Long> offsets = new HashMap<>();
        for (FieldLayout field : ClassLayout.parseClass(cls).fields()) {
            offsets.put(field.name(), field.offset());
        }
        return offsets;
    }

    @Test
    void sharedCountersAreAdjacent() {
        Map<String, Long> offsets = fieldOffsets(SharedCounters.class);
        long delta = Math.abs(offsets.get("counterB") - offsets.get("counterA"));
        // Adjacency (8 bytes apart) is what makes same-line placement the
        // overwhelmingly likely case. Object base alignment is not
        // controlled here, so "same line" is probabilistic per allocation —
        // that caveat is disclosed in java.md; what must never silently
        // change is the adjacency itself.
        assertEquals(8, delta,
            "SharedCounters counters are no longer adjacent — the 'shared line' variant no longer tests what it claims");
    }

    @Test
    void paddedCountersAreAtLeastOneLineApart() {
        Map<String, Long> offsets = fieldOffsets(PaddedCounters.class);
        long delta = Math.abs(offsets.get("counterB") - offsets.get("counterA"));
        assertTrue(delta >= ASSUMED_CACHE_LINE_BYTES,
            "PaddedCounters counters are only " + delta + " bytes apart (< " + ASSUMED_CACHE_LINE_BYTES
                + ") — manual padding was reordered or eliminated; the 'padded' variant is invalid");
    }

    @Test
    void contendedCountersAreAtLeastOneLineApartWhenEnabled() {
        // Surefire runs this JVM with -XX:-RestrictContended (see pom.xml),
        // matching the benchmark fork's flags — this verifies the layout
        // @Contended actually produces under the flags the lab documents.
        Map<String, Long> offsets = fieldOffsets(ContendedCounters.class);
        long delta = Math.abs(offsets.get("counterB") - offsets.get("counterA"));
        assertTrue(delta >= ASSUMED_CACHE_LINE_BYTES,
            "@Contended produced only " + delta + " bytes between counters under -XX:-RestrictContended");
    }
}
