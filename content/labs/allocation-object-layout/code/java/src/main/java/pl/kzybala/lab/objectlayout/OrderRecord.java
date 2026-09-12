package pl.kzybala.lab.objectlayout;

/** The ordersQuotes logical record as a real heap object — one instance per order. */
public final class OrderRecord {
    public long id;
    public long priceTicks;
    public int quantity;
    public int flags;

    public OrderRecord(long id, long priceTicks, int quantity, int flags) {
        this.id = id;
        this.priceTicks = priceTicks;
        this.quantity = quantity;
        this.flags = flags;
    }
}
