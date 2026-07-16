package pl.kzybala.lab.cachehierarchy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Detected (never assumed) cache topology. On Linux, sizes come from
 * {@code /sys/devices/system/cpu/cpu0/cache/index*} — the manifest the
 * working-set scenarios are bound to, so "L1-sized" means sized against
 * THIS host's L1d, not a hardcoded byte count. Off Linux (development
 * hosts) documented fallback sizes are used and flagged
 * {@code detected=false}: acceptable for correctness tests and smoke runs,
 * never for publication evidence (the runner only measures on Linux).
 */
public final class CacheTopology {

    private final long l1dBytes;
    private final long l2Bytes;
    private final long llcBytes;
    private final boolean detected;

    private CacheTopology(long l1dBytes, long l2Bytes, long llcBytes, boolean detected) {
        this.l1dBytes = l1dBytes;
        this.l2Bytes = l2Bytes;
        this.llcBytes = llcBytes;
        this.detected = detected;
    }

    public static CacheTopology detect() {
        long l1 = -1;
        long l2 = -1;
        long llc = -1;
        Path base = Path.of("/sys/devices/system/cpu/cpu0/cache");
        if (Files.isDirectory(base)) {
            try {
                for (int index = 0; index < 8; index++) {
                    Path dir = base.resolve("index" + index);
                    if (!Files.isDirectory(dir)) continue;
                    String type = Files.readString(dir.resolve("type")).trim();
                    int level = Integer.parseInt(Files.readString(dir.resolve("level")).trim());
                    long bytes = parseSize(Files.readString(dir.resolve("size")).trim());
                    if (level == 1 && (type.equals("Data") || type.equals("Unified"))) l1 = bytes;
                    if (level == 2 && !type.equals("Instruction")) l2 = bytes;
                    if (level >= 3 && !type.equals("Instruction")) llc = Math.max(llc, bytes);
                }
            } catch (Exception e) {
                l1 = -1; // fall through to the documented fallback
            }
        }
        if (l1 > 0 && l2 > 0) {
            // Some parts have no L3: treat L2 as the last-level cache then.
            return new CacheTopology(l1, l2, llc > 0 ? llc : l2, true);
        }
        // Documented development fallback (flagged, never publication data):
        // 32 KiB L1d / 1 MiB L2 / 32 MiB LLC.
        return new CacheTopology(32L * 1024, 1024L * 1024, 32L * 1024 * 1024, false);
    }

    static long parseSize(String text) {
        String t = text.toUpperCase();
        if (t.endsWith("K")) return Long.parseLong(t.substring(0, t.length() - 1)) * 1024;
        if (t.endsWith("M")) return Long.parseLong(t.substring(0, t.length() - 1)) * 1024 * 1024;
        return Long.parseLong(t);
    }

    public long l1dBytes() { return l1dBytes; }
    public long l2Bytes() { return l2Bytes; }
    public long llcBytes() { return llcBytes; }
    public boolean isDetected() { return detected; }

    /**
     * Element count (8-byte elements) for a named working set, bound to the
     * detected topology: half of a level's capacity fits comfortably inside
     * it while exceeding the previous level by construction; "memory" is
     * 4× the LLC so no cache level can hold it.
     */
    public int workingSetElements(String name) {
        long bytes = switch (name) {
            case "l1" -> l1dBytes / 2;
            case "l2" -> Math.max(l2Bytes / 2, l1dBytes * 2);
            case "llc" -> Math.max(llcBytes / 2, l2Bytes * 2);
            case "memory" -> llcBytes * 4;
            default -> throw new IllegalArgumentException("unknown working set: " + name);
        };
        return (int) Math.min(bytes / 8, Integer.MAX_VALUE - 8);
    }

    public String toJson(String workingSet, int elements) {
        return "{ \"detected\": " + detected
            + ", \"l1dBytes\": " + l1dBytes
            + ", \"l2Bytes\": " + l2Bytes
            + ", \"llcBytes\": " + llcBytes
            + ", \"workingSet\": \"" + workingSet + "\""
            + ", \"elements\": " + elements
            + ", \"elementBytes\": 8"
            + ", \"workingSetBytes\": " + (8L * elements)
            + ", \"source\": \"" + (detected ? "/sys/devices/system/cpu/cpu0/cache" : "development-fallback (never publication data)") + "\" }";
    }

    public static List<String> workingSetNames() {
        return List.of("l1", "l2", "llc", "memory");
    }
}
