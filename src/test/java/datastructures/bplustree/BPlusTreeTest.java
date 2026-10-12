package datastructures.bplustree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;
import java.util.TreeMap;
import java.util.function.Supplier;

import static datastructures.bplustree.BPlusTreeInspector.checkInvariants;
import static datastructures.bplustree.BPlusTreeInspector.dump;
import static datastructures.bplustree.BPlusTreeInspector.height;
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
}