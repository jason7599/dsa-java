package datastructures.sortedmultiset;

import datastructures.bplustree.BPlusTree;
import datastructures.bst.AVLTree;
import datastructures.skiplist.SkipList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for every SortedMultiset implementation.
 * Runs every test below once per implementation listed in implementations().
 *
 * Run just one implementation:  -Dimpl=skip        (case-insensitive name fragment)
 * Bigger benchmark:             -Dbenchmark.size=1_000_000
 */
@ParameterizedClass
@MethodSource("implementations")
class SortedMultisetTest {

    static final long SEED = 666;

    /** Every implementation under test, with the largest benchmark size it finishes in reasonable time. */
    static Stream<Arguments.ArgumentSet> implementations() {
        String only = System.getProperty("impl");
        return Stream.of(
                // The reference runs the contract too: a test that fails on TreeMap is a wrong test.
                impl("TreeMap (reference)", TreeMapMultiset::new, 10_000_000),
                // Sorted inserts make it a linked list (and recursion gets deep), so keep it small.
//                impl("UnbalancedBST", UnbalancedBinarySearchTree::new, 5_000),
                impl("AVLTree", AVLTree::new, 10_000_000),
                impl("SkipList", () -> new SkipList<>(new Random(SEED)), 10_000_000),
                impl("B+Tree(5)", () -> new BPlusTree<>(5), 10_000_000),
                impl("B+Tree(128)", () -> new BPlusTree<>(128), 10_000_000)
        ).filter(a -> only == null || a.getName().toLowerCase().contains(only.toLowerCase()));
    }

    /** One implementation: a name, a way to make fresh instances, and how big the benchmark may go. */
    record Impl(String name, Supplier<SortedMultiset<Integer>> factory, int maxBenchmarkSize) {}

    private static Arguments.ArgumentSet impl(String name, Supplier<SortedMultiset<Integer>> factory, int maxBenchmarkSize) {
        return Arguments.argumentSet(name, new Impl(name, factory, maxBenchmarkSize));
    }

    static final int BENCHMARK_SIZE =
            Integer.parseInt(System.getProperty("benchmark.size", "1_000_000").replace("_", ""));

    @Parameter(0)
    Impl impl;

    Random rng;
    SortedMultiset<Integer> set;

    @BeforeEach
    void setUp() {
        rng = new Random(SEED);
        set = impl.factory().get();
    }

    // ---- Empty ----

    @Test
    void emptySet() {
        assertEquals(0, set.size());
        assertTrue(set.isEmpty());
        assertFalse(set.contains(1));
        assertEquals(0, set.count(1));
        assertNull(set.min());
        assertNull(set.max());
        assertNull(set.floor(1));
        assertNull(set.ceiling(1));
        assertNull(set.lower(1));
        assertNull(set.higher(1));
        assertFalse(set.iterator().hasNext());
        assertThrows(NoSuchElementException.class, () -> set.iterator().next());
    }

    @Test
    void removeMissingIsNoop() {
        set.remove(1);
        set.removeAll(1);
        assertContents();

        insertAll(5);
        set.remove(1);
        set.removeAll(7);
        assertContents(5);
    }

    // ---- Duplicates ----

    @Test
    void duplicatesCountTowardSize() {
        insertAll(5, 5, 5, 3);
        assertEquals(3, set.count(5));
        assertEquals(1, set.count(3));
        assertContents(3, 5, 5, 5);
    }

    @Test
    void removeTakesOneOccurrence() {
        insertAll(5, 5, 5);
        set.remove(5);
        assertEquals(2, set.count(5));
        assertContents(5, 5);
    }

    @Test
    void removeAllTakesEveryOccurrence() {
        insertAll(5, 5, 5, 3);
        set.removeAll(5);
        assertFalse(set.contains(5));
        assertEquals(0, set.count(5));
        assertContents(3);
    }

    // ---- Navigation ----

    @Test
    void navigation() {
        insertAll(20, 10, 30, 20);

        assertEquals(10, set.min());
        assertEquals(30, set.max());

        // exact hits
        assertEquals(20, set.floor(20));
        assertEquals(20, set.ceiling(20));
        assertEquals(10, set.lower(20));
        assertEquals(30, set.higher(20));

        // between elements
        assertEquals(20, set.floor(25));
        assertEquals(30, set.ceiling(25));
        assertEquals(20, set.lower(25));
        assertEquals(30, set.higher(25));

        // outside the range
        assertNull(set.floor(5));
        assertNull(set.lower(10));
        assertNull(set.ceiling(35));
        assertNull(set.higher(30));
        assertEquals(30, set.floor(99));
        assertEquals(10, set.ceiling(-99));
    }

    // ---- Iteration ----

    @Test
    void iterationIsSortedWithDuplicates() {
        insertAll(3, 1, 2, 3, 1);
        assertContents(1, 1, 2, 3, 3);
    }

    @Test
    void iteratorThrowsWhenExhausted() {
        insertAll(1);
        Iterator<Integer> it = set.iterator();
        assertTrue(it.hasNext());
        assertEquals(1, it.next());
        assertFalse(it.hasNext());
        assertThrows(NoSuchElementException.class, it::next);
    }

    // ---- Regressions (bugs found while building SkipList) ----

    @Test
    void reusableAfterClear() {
        insertAll(1, 2, 3, 3);
        set.clear();
        assertFalse(set.contains(1));
        assertNull(set.min());
        assertContents();

        insertAll(4);
        assertContents(4);
    }

    @Test
    void reusableAfterRemovingLastElement() {
        insertAll(1);
        set.remove(1);
        assertContents();

        insertAll(2, 1);
        assertContents(1, 2);
    }

    // ---- Stress: random operations vs. the reference ----

    static final int STRESS_STEPS = 200_000;
    static final int STRESS_KEY_RANGE = 1_000;  // small, so duplicates and hits are common
    static final int FULL_CHECK_EVERY = 1_000;  // full iteration is O(n), so not every step

    @Test
    void matchesReferenceUnderRandomOperations() {
        SortedMultiset<Integer> ref = new TreeMapMultiset<>();

        for (int step = 1; step <= STRESS_STEPS; step++) {
            int x = rng.nextInt(STRESS_KEY_RANGE);
            int roll = rng.nextInt(10_000);
            String op;
            if (roll < 5_000)      { op = "insert";    set.insert(x);    ref.insert(x); }
            else if (roll < 9_000) { op = "remove";    set.remove(x);    ref.remove(x); }
            else if (roll < 9_999) { op = "removeAll"; set.removeAll(x); ref.removeAll(x); }
            else                   { op = "clear";     set.clear();      ref.clear(); }

            // Seeded, so "step N" is reproducible: set a conditional breakpoint on it.
            final int s = step;
            Supplier<String> where = () -> "step " + s + " after " + op + "(" + x + ")";

            // Probe the key we just touched, plus a random one (sometimes outside the key range).
            assertSameAnswers(ref, x, where);
            assertSameAnswers(ref, rng.nextInt(STRESS_KEY_RANGE + 20) - 10, where);

            if (step % FULL_CHECK_EVERY == 0) {
                assertIterationMatches(ref, where);
            }
        }

        assertIterationMatches(ref, () -> "end of stress test");
    }

    /** Every query's answer for one probe. Comparing records means a failure shows exactly which answer differs. */
    record Answers(int size, boolean contains, int count, Integer min, Integer max,
                   Integer floor, Integer ceiling, Integer lower, Integer higher) {
        static Answers of(SortedMultiset<Integer> s, int probe) {
            return new Answers(s.size(), s.contains(probe), s.count(probe), s.min(), s.max(),
                    s.floor(probe), s.ceiling(probe), s.lower(probe), s.higher(probe));
        }
    }

    void assertSameAnswers(SortedMultiset<Integer> ref, int probe, Supplier<String> where) {
        assertEquals(Answers.of(ref, probe), Answers.of(set, probe),
                () -> impl.name() + " differs from reference at " + where.get() + ", probe " + probe);
    }

    void assertIterationMatches(SortedMultiset<Integer> ref, Supplier<String> where) {
        assertEquals(toList(ref), toList(set),
                () -> impl.name() + " iteration differs from reference at " + where.get());
    }

    // ---- Benchmark (also sanity-checks results on bigger inputs) ----

    static volatile long sink; // consumes query results so the JIT can't skip the work

    @Test
    void benchmark() {
        final int n = BENCHMARK_SIZE;
//        assumeTrue(n <= impl.maxBenchmarkSize(), () -> "too slow for " + impl.name() + " at n = " + n);

        int[] keys = rng.ints(n).toArray();
        int[] probes = new int[n]; // about half hits, half (almost certain) misses
        for (int i = 0; i < n; i++) {
            probes[i] = rng.nextBoolean() ? keys[rng.nextInt(n)] : rng.nextInt();
        }

        Map<String, Double> ms = new LinkedHashMap<>();

        SortedMultiset<Integer> s = impl.factory().get();
        ms.put("insert rand", time(() -> { for (int k : keys) s.insert(k); }));
        assertEquals(n, s.size(), () -> impl.name() + " lost elements on insert");

        ms.put("contains", time(() -> {
            long hits = 0;
            for (int p : probes) if (s.contains(p)) hits++;
            sink += hits;
        }));

        ms.put("floor", time(() -> {
            long acc = 0;
            for (int p : probes) {
                Integer f = s.floor(p);
                if (f != null) acc += f;
            }
            sink += acc;
        }));

        ms.put("iterate", time(() -> {
            long acc = 0;
            for (int v : s) acc += v;
            sink += acc;
        }));

        ms.put("remove", time(() -> { for (int k : keys) s.remove(k); }));
        assertTrue(s.isEmpty(), () -> impl.name() + " not empty after removing everything");

        SortedMultiset<Integer> sorted = impl.factory().get();
        ms.put("insert sorted", time(() -> { for (int i = 0; i < n; i++) sorted.insert(i); }));
        assertEquals(n, sorted.size(), () -> impl.name() + " lost elements on sorted insert");

        StringBuilder header = new StringBuilder(String.format("%-40s", "ms"));
        StringBuilder row = new StringBuilder(String.format("%-40s", impl.name() + String.format(" (n=%,d)", n)));
        ms.forEach((label, millis) -> {
            header.append(String.format("%15s", label));
            row.append(String.format("%15.2f", millis));
        });
        System.out.println(header + "\n" + row + "\n");
    }

    // ---- Helpers ----

    void insertAll(int... values) {
        for (int v : values) set.insert(v);
    }

    /** Checks iteration order, size and isEmpty in one go. */
    void assertContents(int... expected) {
        assertEquals(Arrays.stream(expected).boxed().toList(), toList(set),
                () -> impl.name() + " iterated the wrong elements");
        assertEquals(expected.length, set.size(), () -> impl.name() + " size");
        assertEquals(expected.length == 0, set.isEmpty(), () -> impl.name() + " isEmpty");
    }

    static List<Integer> toList(SortedMultiset<Integer> s) {
        List<Integer> out = new ArrayList<>();
        s.forEach(out::add);
        return out;
    }

    static double time(Runnable r) {
        long start = System.nanoTime();
        r.run();
        return (System.nanoTime() - start) / 1_000_000.0;
    }

    // ---- Reference implementation ----

    /**
     * SortedMultiset backed by java.util.TreeMap (a red-black tree), element -> count.
     * The oracle for the stress test and the baseline row in the benchmark.
     */
    static final class TreeMapMultiset<T extends Comparable<? super T>> implements SortedMultiset<T> {

        private final TreeMap<T, Integer> counts = new TreeMap<>();
        private int size;

        @Override public void insert(T data) { counts.merge(data, 1, Integer::sum); size++; }
        @Override public boolean contains(T data) { return counts.containsKey(data); }
        @Override public int count(T data) { return counts.getOrDefault(data, 0); }

        @Override
        public void remove(T data) {
            Integer c = counts.get(data);
            if (c == null) return;
            if (c == 1) counts.remove(data);
            else counts.put(data, c - 1);
            size--;
        }

        @Override
        public void removeAll(T data) {
            Integer c = counts.remove(data);
            if (c != null) size -= c;
        }

        @Override public void clear() { counts.clear(); size = 0; }
        @Override public T min() { return counts.isEmpty() ? null : counts.firstKey(); }
        @Override public T max() { return counts.isEmpty() ? null : counts.lastKey(); }
        @Override public T floor(T data) { return counts.floorKey(data); }
        @Override public T ceiling(T data) { return counts.ceilingKey(data); }
        @Override public T lower(T data) { return counts.lowerKey(data); }
        @Override public T higher(T data) { return counts.higherKey(data); }
        @Override public int size() { return size; }

        @Override
        public Iterator<T> iterator() {
            return new Iterator<>() {
                private final Iterator<Map.Entry<T, Integer>> entries = counts.entrySet().iterator();
                private T current;
                private int remaining;

                @Override
                public boolean hasNext() {
                    return remaining > 0 || entries.hasNext();
                }

                @Override
                public T next() {
                    if (remaining == 0) { // entries.next() throws NoSuchElementException when exhausted
                        Map.Entry<T, Integer> e = entries.next();
                        current = e.getKey();
                        remaining = e.getValue();
                    }
                    remaining--;
                    return current;
                }
            };
        }
    }
}