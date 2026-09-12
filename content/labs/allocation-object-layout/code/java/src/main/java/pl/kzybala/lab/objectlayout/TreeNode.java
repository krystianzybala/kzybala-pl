package pl.kzybala.lab.objectlayout;

/** The treeNodes logical record as a real linked heap object — a genuine pointer-chasing graph. */
public final class TreeNode {
    public long value;
    public TreeNode left;
    public TreeNode right;

    public TreeNode(long value) {
        this.value = value;
    }
}
