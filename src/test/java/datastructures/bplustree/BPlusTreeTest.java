package datastructures.bplustree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BPlusTreeTest {

    static final long SEED = 666;

    // ---- Golden structures: hand-checked pictures ----
    @Test
    void workedExample() {
        BPlusTree<Integer> t = new BPlusTree<>(3);
        insertAll(t, 10, 20);
        assertEquals("[10 20]", dump(t));

        t.insert(30);  // leaf split, 20 copied up
        assertEquals("[20] / [10] [20 30]", dump(t));

        t.insert(40);  // leaf split, 30 copied up
        assertEquals("[20 30] / [10] [20] [30 40]", dump(t));

        t.insert(50);  // leaf split copies 40 up, root overflows, 30 MOVES up
        assertEquals("[30] / [20] [40] / [10] [20] [30] [40 50]", dump(t));
        checkInvariants(t);
    }

    @Test
    void duplicatesNeverSplit() {
        BPlusTree<Integer> t = new BPlusTree<>(3);
        for (int i = 0; i < 1_000; i++) t.insert(7);
        assertEquals("[7]", dump(t));
        assertEquals(1_000, t.count(7));
        assertEquals(1_000, t.size());
        checkInvariants(t);
    }

    @Test
    void emptyTree() {
        BPlusTree<Integer> t = new BPlusTree<>(3);
        assertEquals("[]", dump(t));
        assertEquals(1, height(t));
        checkInvariants(t);
    }

    // ---- Adversarial insert orders ----

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 5, 128})
    void sortedInserts(int order) {
        BPlusTree<Integer> t = new BPlusTree<>(order);
        for (int i = 0; i < 20_000; i++) {
            t.insert(i);                         // every split happens at the right edge
            if (i % 500 == 0) checkInvariants(t);
        }
        checkInvariants(t);
        assertHeightIsLogarithmic(t, 20_000, order);
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 5, 128})
    void reverseSortedInserts(int order) {
        BPlusTree<Integer> t = new BPlusTree<>(order);
        for (int i = 20_000; i > 0; i--) {
            t.insert(i);                         // every split happens at the left edge
            if (i % 500 == 0) checkInvariants(t);
        }
        checkInvariants(t);
        assertHeightIsLogarithmic(t, 20_000, order);
    }

    // ---- Insert-only stress vs TreeMap (works while remove is still a stub) ----

    static final int STRESS_STEPS = 100_000;
    static final int CHECK_EVERY = 500;  // checkInvariants walks the whole tree, so not every step

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 5, 128})
    void randomInsertsMatchReference(int order) {
        for (int keyRange : new int[]{50, 5_000, Integer.MAX_VALUE}) {  // lots of dups → almost none
            BPlusTree<Integer> t = new BPlusTree<>(order);
            TreeMap<Integer, Integer> ref = new TreeMap<>();
            Random rng = new Random(SEED);

            for (int step = 1; step <= STRESS_STEPS; step++) {
                int x = rng.nextInt(keyRange);
                t.insert(x);
                ref.merge(x, 1, Integer::sum);

                final int s = step;
                Supplier<String> where = () -> "order " + order + ", keyRange " + keyRange + ", step " + s;

                // probe the key just inserted, plus a random one (often missing)
                assertSameAnswers(t, ref, x, where);
                assertSameAnswers(t, ref, rng.nextInt(keyRange), where);

                if (step % CHECK_EVERY == 0) checkInvariants(t);
            }
            checkInvariants(t);
            assertEquals(STRESS_STEPS, t.size(), () -> "size after " + STRESS_STEPS + " inserts");
        }
    }

    // ---- Helpers ----

    static void insertAll(BPlusTree<Integer> t, int... values) {
        for (int v : values) t.insert(v);
    }

    static void assertSameAnswers(BPlusTree<Integer> t, TreeMap<Integer, Integer> ref, int p, Supplier<String> where) {
        Supplier<String> at = () -> where.get() + ", probe " + p;
        assertEquals((int) ref.getOrDefault(p, 0), t.count(p), () -> at.get() + " count");
        assertEquals(ref.floorKey(p), t.floor(p), () -> at.get() + " floor");
        assertEquals(ref.ceilingKey(p), t.ceiling(p), () -> at.get() + " ceiling");
        assertEquals(ref.lowerKey(p), t.lower(p), () -> at.get() + " lower");
        assertEquals(ref.higherKey(p), t.higher(p), () -> at.get() + " higher");
        assertEquals(ref.firstKey(), t.min(), () -> at.get() + " min");
        assertEquals(ref.lastKey(), t.max(), () -> at.get() + " max");
    }

    /**
     * Every non-root internal node has at least ceil(m/2) children, so a tree of height h
     * holds at least 2 * ceil(m/2)^(h-2) leaves. Turned around: h <= 2 + log_{ceil(m/2)}(leaves / 2).
     * Leaves <= distinct keys, so using n is a safe (slightly loose) bound.
     */
    static void assertHeightIsLogarithmic(BPlusTree<Integer> t, int n, int order) {
        int minFanout = (order + 1) / 2;                      // ceil(m/2)
        double bound = 2 + Math.log(Math.max(n / 2.0, 1)) / Math.log(minFanout);
        int h = height(t);
        assertTrue(h <= bound, () -> "height " + h + " exceeds bound " + bound + " for n=" + n + ", order=" + order);
    }

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
    static <T extends Comparable<? super T>> int checkNode(BPlusTree<T> tree, BPlusTree.Node<T> node, T lo, T hi, int depth, AtomicInteger leafDepth, List<BPlusTree.LeafNode<T>> leaves) {
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

    static <T extends Comparable<? super T>> String where(BPlusTree.Node<T> node, int depth) {
        return (node instanceof BPlusTree.LeafNode ? "leaf" : "internal node") + " at depth " + depth
                + " " + Arrays.toString(Arrays.copyOf(node.keys, node.keyCount));
    }

    static void fail(String msg) {
        throw new IllegalStateException(msg);
    }

}