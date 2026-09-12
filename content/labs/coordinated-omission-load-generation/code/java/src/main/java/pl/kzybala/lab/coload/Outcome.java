package pl.kzybala.lab.coload;

public record Outcome(
        int produced,
        int admitted,
        int missed,
        long recordedSamples,
        long totalServiceTimeNanos,
        long totalResponseTimeNanos,
        long maxResponseTimeNanos) {

    public boolean admissionInvariantHolds() {
        return produced == admitted + missed;
    }
}
