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
    static final int[] ORDERS = {3, 4, 5, 128};

    // =====================================================================
    // Insert: golden structures
    // =====================================================================

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

    // =====================================================================
    // Delete: golden structures, one per repair operation
    // =====================================================================

    @Test
    void removeWithoutUnderflowTouchesOnlyTheLeaf() {
        BPlusTree<Integer> t = tree(10, 20, 30, 40, 50);
        t.remove(50);  // [40 50] -> [40], still >= minKeys
        assertEquals("[30] / [20] [40] / [10] [20] [30] [40]", dump(t));
        checkInvariants(t);
    }

    @Test
    void leafBorrowsFromRightSibling() {
        BPlusTree<Integer> t = tree(10, 20, 30, 40, 50);
        assertEquals("[30] / [20] [40] / [10] [20] [30] [40 50]", dump(t));

        t.remove(30);  // [30] empties; right sibling [40 50] lends 40; separator re-copied as 50
        assertEquals("[30] / [20] [50] / [10] [20] [40] [50]", dump(t));
        checkInvariants(t);  // root's 30 is now stale but still a valid signpost
    }

    @Test
    void leafBorrowsFromLeftSibling() {
        BPlusTree<Integer> t = tree(10, 20, 30, 5);
        assertEquals("[20] / [5 10] [20 30]", dump(t));

        t.remove(20);
        assertEquals("[20] / [5 10] [30]", dump(t));  // no underflow, stale separator stays

        t.remove(30);  // [30] empties; left sibling [5 10] lends 10; separator re-copied as 10
        assertEquals("[10] / [5] [10]", dump(t));
        checkInvariants(t);
    }

    @Test
    void leafMergeCascadesIntoInternalMergeAndRootCollapse() {
        BPlusTree<Integer> t = tree(10, 20, 30, 40, 50);
        assertEquals(3, height(t));

        // [20] empties, can't borrow from [10] -> leaf merge; its parent [20] loses its key ->
        // can't borrow from [40] -> internal merge pulls 30 down -> root is empty -> collapse.
        t.remove(20);
        assertEquals("[30 40] / [10] [30] [40 50]", dump(t));
        assertEquals(2, height(t));
        checkInvariants(t);
    }

    @Test
    void internalBorrowsFromRightSibling() {
        BPlusTree<Integer> t = tree(10, 20, 30, 40, 50, 60);
        assertEquals("[30] / [20] [40 50] / [10] [20] [30] [40] [50 60]", dump(t));

        // leaf merge empties internal [20]; right internal sibling [40 50] lends:
        // 30 comes down, 40 goes up, leaf [30] crosses over.
        t.remove(20);
        assertEquals("[40] / [30] [50] / [10] [30] [40] [50 60]", dump(t));
        checkInvariants(t);
    }

    @Test
    void internalBorrowsFromLeftSibling() {
        BPlusTree<Integer> t = tree(10, 20, 30, 40, 50, 22, 24);
        t.remove(50);
        assertEquals("[30] / [20 22] [40] / [10] [20] [22 24] [30] [40]", dump(t));

        // leaf merge empties internal [40]; left internal sibling [20 22] lends:
        // 30 comes down, 22 goes up, leaf [22 24] crosses over.
        t.remove(40);
        assertEquals("[22] / [20] [30] / [10] [20] [22 24] [30]", dump(t));
        checkInvariants(t);
    }

    @Test
    void deletingEverythingShrinksBackToOneEmptyLeaf() {
        BPlusTree<Integer> t = tree(10, 20, 30, 40, 50);
        String[] expected = {
                "[30 40] / [20] [30] [40 50]",
                "[40] / [30] [40 50]",
                "[50] / [40] [50]",
                "[50]",
                "[]",
        };
        int[] order = {10, 20, 30, 40, 50};
        for (int k = 0; k < order.length; k++) {
            t.remove(order[k]);
            assertEquals(expected[k], dump(t), "after removing " + order[k]);
            checkInvariants(t);
        }
        assertEquals(1, height(t));
        assertEquals(0, t.size());

        insertAll(t, 1, 2, 3);  // still usable afterwards
        assertEquals("[2] / [1] [2 3]", dump(t));
        checkInvariants(t);
    }

    // =====================================================================
    // Delete: counts and no-ops
    // =====================================================================

    @Test
    void removeDecrementsDuplicatesWithoutStructuralChange() {
        BPlusTree<Integer> t = tree(10, 20, 30, 40, 50, 30, 30);
        String before = dump(t);
        t.remove(30);
        assertEquals(before, dump(t));
        assertEquals(2, t.count(30));
        assertEquals(6, t.size());
        checkInvariants(t);
    }

    @Test
    void removeAllDropsEveryCopy() {
        BPlusTree<Integer> t = tree(10, 20, 30, 40, 50, 30, 30);
        t.removeAll(30);
        assertEquals(0, t.count(30));
        assertEquals(4, t.size());
        checkInvariants(t);
    }

    @Test
    void removingMissingKeysIsANoop() {
        BPlusTree<Integer> t = tree(10, 20, 30, 40, 50);
        String before = dump(t);
        t.remove(25);
        t.removeAll(99);
        t.remove(-1);
        assertEquals(before, dump(t));
        assertEquals(5, t.size());

        BPlusTree<Integer> empty = new BPlusTree<>(3);
        empty.remove(1);
        empty.removeAll(1);
        assertEquals("[]", dump(empty));
        checkInvariants(empty);
    }

    // =====================================================================
    // Adversarial orders: insert everything, then delete everything
    // =====================================================================

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

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 5, 128})
    void deleteEverythingInVariousOrders(int order) {
        final int n = 5_000;
        List<Integer> keys = new ArrayList<>();
        for (int i = 0; i < n; i++) keys.add(i);

        List<Integer> ascending = new ArrayList<>(keys);
        List<Integer> descending = new ArrayList<>(keys);
        Collections.reverse(descending);
        List<Integer> shuffled = new ArrayList<>(keys);
        Collections.shuffle(shuffled, new Random(SEED));
        List<Integer> outsideIn = new ArrayList<>();  // alternate ends: hammers both edges
        for (int lo = 0, hi = n - 1; lo <= hi; lo++, hi--) {
            outsideIn.add(lo);
            if (lo != hi) outsideIn.add(hi);
        }

        for (List<Integer> deleteOrder : List.of(ascending, descending, shuffled, outsideIn)) {
            BPlusTree<Integer> t = new BPlusTree<>(order);
            for (int k : shuffled) t.insert(k);

            int step = 0;
            for (int k : deleteOrder) {
                t.remove(k);
                if (++step % 250 == 0) checkInvariants(t);
            }
            checkInvariants(t);
            assertEquals(0, t.size(), "order " + order + ": size after deleting everything");
            assertEquals("[]", dump(t), "order " + order + ": tree should collapse to one empty leaf");
            assertEquals(1, height(t));
        }
    }

    // =====================================================================
    // Random stress vs TreeMap
    // =====================================================================

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
                assertSameAnswers(t, ref, x, where);
                assertSameAnswers(t, ref, rng.nextInt(keyRange), where);

                if (step % CHECK_EVERY == 0) checkInvariants(t);
            }
            checkInvariants(t);
            assertEquals(STRESS_STEPS, t.size(), () -> "size after " + STRESS_STEPS + " inserts");
        }
    }

    /**
     * Mixed insert / remove / removeAll against TreeMap, with structural checks along the way.
     * Small key ranges keep the tree small, so it constantly grows AND shrinks
     * (splits, borrows, merges, root splits and root collapses all get exercised).
     */
    @ParameterizedTest
    @ValueSource(ints = {3, 4, 5, 128})
    void randomInsertsAndDeletesMatchReference(int order) {
        for (int keyRange : new int[]{20, 300, 5_000}) {
            BPlusTree<Integer> t = new BPlusTree<>(order);
            TreeMap<Integer, Integer> ref = new TreeMap<>();
            Random rng = new Random(SEED);

            for (int step = 1; step <= STRESS_STEPS; step++) {
                int x = rng.nextInt(keyRange);
                int roll = rng.nextInt(100);
                String op;
                if (roll < 50) {
                    op = "insert";
                    t.insert(x);
                    ref.merge(x, 1, Integer::sum);
                } else if (roll < 90) {
                    op = "remove";
                    t.remove(x);
                    ref.computeIfPresent(x, (k, c) -> c == 1 ? null : c - 1);
                } else {
                    op = "removeAll";
                    t.removeAll(x);
                    ref.remove(x);
                }

                final int s = step;
                Supplier<String> where = () -> "order " + order + ", keyRange " + keyRange
                        + ", step " + s + " after " + op + "(" + x + ")";

                assertEquals(refSize(ref), t.size(), () -> where.get() + " size");
                assertSameAnswers(t, ref, x, where);
                assertSameAnswers(t, ref, rng.nextInt(keyRange + 10) - 5, where);

                if (step % CHECK_EVERY == 0) checkInvariants(t);
            }
            checkInvariants(t);
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    static BPlusTree<Integer> tree(int... values) {
        BPlusTree<Integer> t = new BPlusTree<>(3);
        insertAll(t, values);
        checkInvariants(t);
        return t;
    }

    static int refSize(TreeMap<Integer, Integer> ref) {
        int n = 0;
        for (int c : ref.values()) n += c;
        return n;
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