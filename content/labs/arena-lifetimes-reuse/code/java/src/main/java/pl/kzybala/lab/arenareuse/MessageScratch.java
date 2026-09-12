package pl.kzybala.lab.arenareuse;

/** The small mutable holder every messageBatches variant needs — one per decoded message. */
public final class MessageScratch {
    public long id;
    public long value;
    public int flag;
}
