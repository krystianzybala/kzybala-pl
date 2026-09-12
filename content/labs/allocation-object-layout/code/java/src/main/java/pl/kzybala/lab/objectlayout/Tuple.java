package pl.kzybala.lab.objectlayout;

/** The smallTuples logical record — the smallest record in this lab, sized to make header overhead ratio starkest. */
public final class Tuple {
    public int x;
    public int y;

    public Tuple(int x, int y) {
        this.x = x;
        this.y = y;
    }
}
