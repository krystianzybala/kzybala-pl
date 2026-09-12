package pl.kzybala.lab.cpuaffinity;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Best-effort real hardware topology, read from {@code /sys} on Linux. Every field is an
 * {@link OptionalInt} rather than a fabricated default: when this repository's development
 * host (or a Linux host lacking multiple NUMA nodes / SMT) cannot supply a real answer, the
 * field is empty and callers must record that variant's placement evidence as unavailable
 * (design.md: "must mark evidence as unavailable rather than fail the ordinary content build").
 */
public record Topology(
        boolean supported,
        int cpuCount,
        OptionalInt isolatedCpu,
        OptionalInt smtSiblingCpu,
        OptionalInt sameNumaCpu,
        OptionalInt remoteNumaCpu) {

    public static Topology detect() {
        if (!CpuAffinity.isSupported()) {
            return new Topology(false, Runtime.getRuntime().availableProcessors(),
                    OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty());
        }
        int cpuCount = Runtime.getRuntime().availableProcessors();
        OptionalInt isolated = cpuCount > 2 ? OptionalInt.of(2) : OptionalInt.of(0);
        OptionalInt smtSibling = readSmtSibling(isolated.orElse(0));
        List<List<Integer>> nodes = readNumaNodes();
        OptionalInt sameNuma = OptionalInt.empty();
        OptionalInt remoteNuma = OptionalInt.empty();
        for (List<Integer> node : nodes) {
            if (node.contains(isolated.orElse(-1))) {
                for (int c : node) {
                    if (c != isolated.orElse(-1)) {
                        sameNuma = OptionalInt.of(c);
                        break;
                    }
                }
            } else if (!node.isEmpty()) {
                remoteNuma = OptionalInt.of(node.get(0));
            }
        }
        return new Topology(true, cpuCount, isolated, smtSibling, sameNuma, remoteNuma);
    }

    private static OptionalInt readSmtSibling(int cpu) {
        try {
            Path p = Path.of("/sys/devices/system/cpu/cpu" + cpu + "/topology/thread_siblings_list");
            if (!Files.isReadable(p)) return OptionalInt.empty();
            String line = Files.readString(p).trim();
            for (int id : parseIdList(line)) {
                if (id != cpu) return OptionalInt.of(id);
            }
        } catch (IOException | NumberFormatException ignored) {
            // fall through
        }
        return OptionalInt.empty();
    }

    private static List<List<Integer>> readNumaNodes() {
        List<List<Integer>> nodes = new ArrayList<>();
        Path base = Path.of("/sys/devices/system/node");
        if (!Files.isDirectory(base)) return nodes;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(base, "node[0-9]*")) {
            for (Path nodeDir : stream) {
                Path cpulist = nodeDir.resolve("cpulist");
                if (Files.isReadable(cpulist)) {
                    nodes.add(parseIdList(Files.readString(cpulist).trim()));
                }
            }
        } catch (IOException ignored) {
            // fall through with whatever was collected
        }
        return nodes;
    }

    private static List<Integer> parseIdList(String spec) {
        List<Integer> ids = new ArrayList<>();
        if (spec.isBlank()) return ids;
        for (String part : spec.split(",")) {
            if (part.contains("-")) {
                String[] bounds = part.split("-");
                int lo = Integer.parseInt(bounds[0].trim());
                int hi = Integer.parseInt(bounds[1].trim());
                for (int i = lo; i <= hi; i++) ids.add(i);
            } else if (!part.isBlank()) {
                ids.add(Integer.parseInt(part.trim()));
            }
        }
        return ids;
    }
}
