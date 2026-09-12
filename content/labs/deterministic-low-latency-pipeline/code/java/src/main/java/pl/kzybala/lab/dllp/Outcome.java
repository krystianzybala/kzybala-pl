package pl.kzybala.lab.dllp;

public record Outcome(int produced, int delivered, int dropped, int restartLoss, long checksum, int maxShardDepth) {
    public boolean invariantHolds() {
        return produced == delivered + dropped + restartLoss;
    }
}
