package datastructures.bplustree;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class BPlusTreeInspector {

    private BPlusTreeInspector() {}

    static <T extends Comparable<? super T>> void checkInvariants(BPlusTree<T> tree) {
        // leaf nodes in tree order, collected by checkNode recursion
        List<BPlusTree.LeafNode<T>> leaves = new ArrayList<>();

        int total = checkNode(tree, tree.root, null, null, 0, new AtomicInteger(-1), leaves);

        if (total != tree.size()) fail("counts sum to " + total + " but size() is " + tree.size());

        // The leaf chain must visit exactly the leaves in tree order, forwards and backwards.
        BPlusTree.LeafNode<T> expectedPrev = null;
        BPlusTree.LeafNode<T> cur = leaves.getFirst();

        if (cur.prev != null) fail("leftmost leaf has a prev pointer");

        for (int i = 0; i < leaves.size(); i++) {
            if (cur != leaves.get(i)) fail("leaf chain diverges from tree order at leaf #" + i);
            if (cur.prev != expectedPrev) fail("broken prev pointer at leaf #" + i);
            expectedPrev = cur;
            cur = cur.next;
        }

        if (cur != null) fail("rightmost leaf has a next pointer");
    }

    // Recursively checks the subtree under node, whose keys must satisfy lo <= key < hi (null = unbounded).
    // Returns its count sum.
    private static <T extends Comparable<? super T>> int checkNode(BPlusTree<T> tree, BPlusTree.Node<T> node, T lo, T hi, int depth, AtomicInteger leafDepth, List<BPlusTree.LeafNode<T>> leaves) {
        int maxKeys = tree.order - 1;
        int minKeys = (int) Math.ceil(tree.order / 2.0) - 1;   // ceil(m/2) children for internal nodes
        boolean isRoot = node == tree.root;

        // occupancy
        if (node.keyCount > maxKeys) fail(where(node, depth) + " overfull: " + node.keyCount + " keys");
        if (!isRoot && node.keyCount < minKeys) fail(where(node, depth) + " underfull: " + node.keyCount + " keys");

        if (lo != null && node.keys[0].compareTo(lo) < 0) fail(where(node, depth) + " < lower bound " + lo);
        if (hi != null && node.keys[node.keyCount - 1].compareTo(hi) >= 0) fail(where(node, depth) + " >= upper bound " + hi);

        // keys: strictly ascending and inside [lo, hi)
        for (int i = 0; i < node.keyCount; i++) {
            T k = node.keys[i];
            if (k == null) fail(where(node, depth) + " null key at " + i);
            if (i > 0 && node.keys[i - 1].compareTo(k) >= 0) fail(where(node, depth) + " keys not strictly ascending");
        }

        // staleness check, this matters for min and max
        for (int i = node.keyCount; i < node.keys.length; i++) {
            if (node.keys[i] != null) fail(where(node, depth) + " stale key in unused slot " + i);
        }

        return switch (node) {
            case BPlusTree.InternalNode<T> in -> {
                if (isRoot && in.keyCount < 1) fail("internal root with fewer than 2 children");

                int sum = 0;
                for (int i = 0; i <= in.keyCount; i++) {
                    BPlusTree.Node<T> child = in.children[i];
                    if (child == null) fail(where(node, depth) + " null child at " + i);

                    T childLo = (i == 0) ? lo : in.keys[i - 1];
                    T childHi = (i == in.keyCount) ? hi : in.keys[i];
                    sum += checkNode(tree, child, childLo, childHi, depth + 1, leafDepth, leaves);
                }
                for (int i = in.keyCount + 1; i < in.children.length; i++) {
                    if (in.children[i] != null) fail(where(node, depth) + " stale child in unused slot " + i);
                }
                yield sum;
            }
            case BPlusTree.LeafNode<T> ln -> {
                if (leafDepth.get() == -1) leafDepth.set(depth);
                else if (leafDepth.get() != depth) fail("leaves at different depths: " + leafDepth.get() + " and " + depth);

                int sum = 0;
                for (int i = 0; i < ln.keyCount; i++) {
                    if (ln.counts[i] < 1) fail(where(node, depth) + " count " + ln.counts[i] + " for key " + ln.keys[i]);
                    sum += ln.counts[i];
                }
                leaves.add(ln);
                yield sum;
            }
        };
    }

    // Level-by-level picture, e.g. "[30] / [20] [40] / [10] [20] [30] [40 50]"
    static <T extends Comparable<? super T>> String dump(BPlusTree<T> tree) {
        StringBuilder sb = new StringBuilder();
        List<BPlusTree.Node<T>> level = List.of(tree.root);
        while (!level.isEmpty()) {
            if (!sb.isEmpty()) sb.append(" / ");
            List<BPlusTree.Node<T>> next = new ArrayList<>();
            for (int j = 0; j < level.size(); j++) {
                BPlusTree.Node<T> n = level.get(j);
                if (j > 0) sb.append(' ');
                sb.append('[');
                for (int i = 0; i < n.keyCount; i++) {
                    if (i > 0) sb.append(' ');
                    sb.append(n.keys[i]);
                }
                sb.append(']');
                if (n instanceof BPlusTree.InternalNode<T> in) {
                    for (int i = 0; i <= in.keyCount; i++) next.add(in.children[i]);
                }
            }
            level = next;
        }
        return sb.toString();
    }

    static <T extends Comparable<? super T>> int height(BPlusTree<T> tree) {
        int h = 1;
        for (BPlusTree.Node<T> n = tree.root; n instanceof BPlusTree.InternalNode<T> in; n = in.children[0]) h++;
        return h;
    }

    private static <T extends Comparable<? super T>> String where(BPlusTree.Node<T> node, int depth) {
        return (node instanceof BPlusTree.LeafNode ? "leaf" : "internal node") + " at depth " + depth
                + " " + Arrays.toString(Arrays.copyOf(node.keys, node.keyCount));
    }

    private static void fail(String msg) {
        throw new IllegalStateException(msg);
    }


}