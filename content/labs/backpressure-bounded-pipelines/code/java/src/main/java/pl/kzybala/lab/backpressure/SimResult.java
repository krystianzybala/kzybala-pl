package pl.kzybala.lab.backpressure;

public record SimResult(
        int produced,
        int delivered,
        int rejected,
        int dropped,
        int superseded,
        int maxQueueDepth) {

    public boolean invariantHolds() {
        return produced == delivered + rejected + dropped + superseded;
    }
}
