package pl.kzybala.lab.cpuaffinity;

import java.util.OptionalInt;

public record PlacementResult(PlacementTarget target, boolean pinned, OptionalInt cpu, long checksum) {}
