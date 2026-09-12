package pl.kzybala.lab.udpingest;

/**
 * Explicit accounting for one ingest run — every datagram sent is
 * accounted for as exactly one of: delivered (received, queued and
 * validated correctly), application-dropped (received off the socket but
 * discarded because the bounded processing path was full — an explicit,
 * counted drop, never a silent one), or corrupted (delivered but failed
 * payload validation, which should never happen over loopback and is
 * asserted against in tests). "Kernel-level" drops (the OS discarding a
 * datagram before this process ever calls receive) are a distinct
 * category this in-process accounting cannot observe directly — see
 * theory.md's "ignoring kernel drops" trap and Assumptions and scope.
 */
public record IngestResult(long delivered, long applicationDropped, long corrupted) {

    public long accountedFor() {
        return delivered + applicationDropped + corrupted;
    }
}
