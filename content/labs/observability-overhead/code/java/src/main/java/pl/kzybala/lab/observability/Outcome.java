package pl.kzybala.lab.observability;

public record Outcome(
        long checksum,
        int calls,
        long formatCount,
        long loggedCount,
        long droppedCount,
        int distinctLabelsSeen,
        long labelHitsTotal,
        long sampledCount) {}
