package datastructures.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.*;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for every Cache implementation, in three layers:
 *   1. Contract:      what any cache promises, whatever its eviction policy.
 *   2. Never lies:    random ops; a get may miss, but a hit must return the last value put.
 *   3. Exact policy:  scenario tests + a stress test against a reference model of the same policy.
 *                     These skip themselves for implementations of other policies.
 * Plus a benchmark that reports hit rate (where policies differ) and time.
 *
 * Run just one implementation:  -Dimpl=lrucache      (case-insensitive name fragment)
 * Longer benchmark:             -Dbenchmark.size=10_000_000
 */
@ParameterizedClass
@MethodSource("implementations")
class CacheTest {

    static final long SEED = 666;

    enum Policy { LRU, CLOCK }

    /** Every implementation under test: a name, a factory taking the capacity, and its eviction policy. */
    static Stream<Arguments.ArgumentSet> implementations() {
        String only = System.getProperty("impl");
        return Stream.of(
                // The reference runs the tests too: a test that fails on LinkedHashMap is a wrong test.
                impl("LinkedHashMap (LRU reference)", ReferenceLRU::new, Policy.LRU),
                impl("Second-chance FIFO (CLOCK reference)", ReferenceSecondChance::new, Policy.CLOCK),
                impl("LRUCache", LRUCache::new, Policy.LRU),
                impl("ClockCache", ClockCache::new, Policy.CLOCK)
        ).filter(a -> only == null || a.getName().toLowerCase().contains(only.toLowerCase()));
    }

    record Impl(String name, IntFunction<Cache<Integer, Integer>> factory, Policy policy) {}

    private static Arguments.ArgumentSet impl(String name, IntFunction<Cache<Integer, Integer>> factory, Policy policy) {
        return Arguments.argumentSet(name, new Impl(name, factory, policy));
    }

    /** An exact model of a policy, used as the oracle in the exact-policy stress test. Null = none yet. */
    static Cache<Integer, Integer> referenceFor(Policy policy, int capacity) {
        return switch (policy) {
            case LRU -> new ReferenceLRU<>(capacity);
            case CLOCK -> new ReferenceSecondChance<>(capacity);
        };
    }

    @Parameter(0)
    Impl impl;

    Random rng;

    @BeforeEach
    void setUp() {
        rng = new Random(SEED);
    }

    Cache<Integer, Integer> newCache(int capacity) {
        return impl.factory().apply(capacity);
    }

    // ==== 1. Contract: any cache ====

    @Test
    void rejectsCapacityBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> newCache(0));
        assertThrows(IllegalArgumentException.class, () -> newCache(-1));
    }

    @Test
    void emptyCache() {
        Cache<Integer, Integer> c = newCache(4);
        assertEquals(4, c.capacity());
        assertEquals(0, c.size());
        assertTrue(c.isEmpty());
        assertNull(c.get(1));
        assertEquals(0, c.size(), "a miss must not add anything");
    }

    @Test
    void putThenGet() {
        Cache<Integer, Integer> c = newCache(4);
        c.put(1, 10);
        assertEquals(10, c.get(1));
        assertEquals(1, c.size());
        assertTrue(!c.isEmpty());
    }

    @Test
    void putOverwrites() {
        Cache<Integer, Integer> c = newCache(4);
        c.put(1, 10);
        c.put(1, 11);
        assertEquals(11, c.get(1));
        assertEquals(1, c.size(), "overwriting must not grow the cache");
    }

    @Test
    void sizeIsCappedAtCapacity() {
        Cache<Integer, Integer> c = newCache(3);
        for (int i = 0; i < 10; i++) c.put(i, i);
        assertEquals(3, c.size());
        assertEquals(9, c.get(9), "the entry just put must survive its own insertion");
    }

    @Test
    void capacityOne() {
        Cache<Integer, Integer> c = newCache(1);
        c.put(1, 10);
        c.put(2, 20);
        assertNull(c.get(1));
        assertEquals(20, c.get(2));
        assertEquals(1, c.size());
    }

    // ==== 2. Never lies: any cache ====

    static final int[] STRESS_CAPACITIES = {1, 2, 16, 64};
    static final int STRESS_STEPS = 50_000; // per capacity

    /** A cache may forget, but never lie: every hit returns the last value put for that key. */
    @Test
    void neverLiesUnderRandomOperations() {
        for (int capacity : STRESS_CAPACITIES) {
            Cache<Integer, Integer> c = newCache(capacity);
            Map<Integer, Integer> truth = new HashMap<>(); // last value put, per key
            int keyRange = capacity * 4;                    // small enough for frequent hits AND evictions

            for (int step = 1; step <= STRESS_STEPS; step++) {
                final int s = step;
                int k = rng.nextInt(keyRange);

                if (rng.nextBoolean()) {
                    int v = rng.nextInt();
                    c.put(k, v);
                    truth.put(k, v);
                } else {
                    Integer got = c.get(k);
                    if (got != null) {
                        assertEquals(truth.get(k), got, () -> where(capacity, s) + ": get(" + k + ") returned a wrong/stale value");
                    }
                }

                // No explicit removal, so a cache only evicts when full: the size is exact.
                assertEquals(Math.min(truth.size(), capacity), c.size(), () -> where(capacity, s) + ": wrong size");
            }
        }
    }

    // ==== 3. Exact policy ====

    @Test
    void lruGetRefreshesRecency() {
        assumeTrue(impl.policy() == Policy.LRU);
        Cache<Integer, Integer> c = newCache(2);
        c.put(1, 10);
        c.put(2, 20);
        c.get(1);      // 1 is now most recent, 2 is oldest
        c.put(3, 30);  // evicts 2
        assertNull(c.get(2));
        assertEquals(10, c.get(1));
        assertEquals(30, c.get(3));
        assertEquals(2, c.size());
    }

    @Test
    void lruPutOnExistingRefreshesRecency() {
        assumeTrue(impl.policy() == Policy.LRU);
        Cache<Integer, Integer> c = newCache(2);
        c.put(1, 10);
        c.put(2, 20);
        c.put(1, 11);  // update counts as a use: 2 is oldest now
        c.put(3, 30);  // evicts 2
        assertNull(c.get(2));
        assertEquals(11, c.get(1));
        assertEquals(30, c.get(3));
    }

    @Test
    void lruMissDoesNotChangeOrder() {
        assumeTrue(impl.policy() == Policy.LRU);
        Cache<Integer, Integer> c = newCache(2);
        c.put(1, 10);
        c.put(2, 20);
        c.get(99);     // miss: nothing moves, 1 is still oldest
        c.put(3, 30);  // evicts 1
        assertNull(c.get(1));
        assertEquals(20, c.get(2));
        assertEquals(30, c.get(3));
    }

    /** Same random ops on the implementation and an exact model of its policy: every hit/miss must match. */
    @Test
    void matchesReferencePolicyExactly() {
        assumeTrue(referenceFor(impl.policy(), 1) != null, () -> "no reference model for " + impl.policy() + " yet");

        for (int capacity : STRESS_CAPACITIES) {
            Cache<Integer, Integer> ref = referenceFor(impl.policy(), capacity);
            Cache<Integer, Integer> c = newCache(capacity);
            int keyRange = capacity * 4;

            for (int step = 1; step <= STRESS_STEPS; step++) {
                final int s = step;
                int k = rng.nextInt(keyRange);

                if (rng.nextBoolean()) {
                    int v = rng.nextInt();
                    c.put(k, v);
                    ref.put(k, v);
                } else {
                    assertEquals(ref.get(k), c.get(k), () -> where(capacity, s) + ": get(" + k + ") differs from the reference");
                }
                assertEquals(ref.size(), c.size(), () -> where(capacity, s) + ": size differs from the reference");
            }
        }
    }

    /** Seeded, so "capacity C, step N" is reproducible: set a conditional breakpoint on it. */
    String where(int capacity, int step) {
        return impl.name() + " at capacity " + capacity + ", step " + step;
    }

    // ==== Benchmark: hit rate per workload, plus time ====

    static final int BENCHMARK_OPS =
            Integer.parseInt(System.getProperty("benchmark.size", "1_000_000").replace("_", ""));
    static final int BENCHMARK_CAPACITY = 1_000;
    static final int KEY_UNIVERSE = 100_000;

    enum Workload {
        /** A few hot keys get most of the traffic, like most real systems. */
        ZIPF,
        /** No locality at all: any policy hits about capacity / KEY_UNIVERSE. */
        UNIFORM,
        /** A hot set that fits, interrupted by sequential scans of never-reused keys (a table scan). */
        HOT_WITH_SCANS,
        /** Cycle over capacity + 1 keys: LRU's worst case, it always evicts the key needed next. */
        LOOP;

        int[] generate(int n, int capacity, Random rng) {
            int[] keys = new int[n];
            switch (this) {
                case ZIPF -> {
                    double[] cdf = zipfCdf(KEY_UNIVERSE, 1.0);
                    for (int i = 0; i < n; i++) keys[i] = sample(cdf, rng);
                }
                case UNIFORM -> {
                    for (int i = 0; i < n; i++) keys[i] = rng.nextInt(KEY_UNIVERSE);
                }
                case HOT_WITH_SCANS -> {
                    int hotSet = capacity * 9 / 10;
                    int nextCold = KEY_UNIVERSE; // cold keys are never repeated
                    int i = 0;
                    while (i < n) {
                        for (int j = 0; j < 2 * capacity && i < n; j++) keys[i++] = rng.nextInt(hotSet);
                        for (int j = 0; j < 2 * capacity && i < n; j++) keys[i++] = nextCold++;
                    }
                }
                case LOOP -> {
                    for (int i = 0; i < n; i++) keys[i] = i % (capacity + 1);
                }
            }
            return keys;
        }

        static double[] zipfCdf(int n, double s) {
            double[] cdf = new double[n];
            double sum = 0;
            for (int i = 0; i < n; i++) {
                sum += 1.0 / Math.pow(i + 1, s);
                cdf[i] = sum;
            }
            for (int i = 0; i < n; i++) cdf[i] /= sum;
            return cdf;
        }

        static int sample(double[] cdf, Random rng) {
            int idx = Arrays.binarySearch(cdf, rng.nextDouble());
            if (idx < 0) idx = -idx - 1;               // insertion point
            return Math.min(idx, cdf.length - 1);       // guard against rounding at the top
        }
    }

    @Test
    void benchmark() {
        String title = impl.name() + String.format(" (ops=%,d, cap=%,d)", BENCHMARK_OPS, BENCHMARK_CAPACITY);
        StringBuilder header = new StringBuilder(String.format("%-50s", title));
        StringBuilder hitRow = new StringBuilder(String.format("%-50s", "  hit %"));
        StringBuilder msRow = new StringBuilder(String.format("%-50s", "  ms"));

        for (Workload w : Workload.values()) {
            int[] keys = w.generate(BENCHMARK_OPS, BENCHMARK_CAPACITY, rng); // same seed: every impl sees the same keys
            Cache<Integer, Integer> c = newCache(BENCHMARK_CAPACITY);

            // Load-on-miss, like a real read-through cache: miss -> "fetch" -> put.
            long misses = 0;
            long start = System.nanoTime();
            for (int k : keys) {
                Integer v = c.get(k);
                if (v == null) {
                    misses++;
                    c.put(k, k);
                } else if (v != k) {
                    fail(impl.name() + " returned " + v + " for key " + k + " on " + w);
                }
            }
            double ms = (System.nanoTime() - start) / 1_000_000.0;

            assertTrue(c.size() <= BENCHMARK_CAPACITY, () -> impl.name() + " grew past capacity on " + w);

            header.append(String.format("%16s", w));
            hitRow.append(String.format("%16.1f", 100.0 * (keys.length - misses) / keys.length));
            msRow.append(String.format("%16.2f", ms));
        }

        System.out.println(header + "\n" + hitRow + "\n" + msRow + "\n");
    }

    // ==== Reference model ====

    /** Exact LRU in a few lines: LinkedHashMap in access order, evicting the eldest when over capacity. */
    static final class ReferenceLRU<K, V> extends Cache<K, V> {

        private final LinkedHashMap<K, V> map;

        ReferenceLRU(int capacity) {
            super(capacity);
            this.map = new LinkedHashMap<>(16, 0.75f, true) { // true = access order: get() moves to the end
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                    return size() > ReferenceLRU.this.capacity;
                }
            };
        }

        @Override public V get(K key) { return map.get(key); }
        @Override public void put(K key, V value) { map.put(key, value); }
        @Override public int size() { return map.size(); }
    }

    /** Exact CLOCK, built differently: a second-chance FIFO queue where entries really do move. */
    static final class ReferenceSecondChance<K, V> extends Cache<K, V> {

        private final ArrayDeque<K> queue = new ArrayDeque<>(); // front = next eviction candidate
        private final Map<K, V> values = new HashMap<>();
        private final Map<K, Boolean> referenced = new HashMap<>();

        ReferenceSecondChance(int capacity) {
            super(capacity);
        }

        @Override
        public V get(K key) {
            if (!values.containsKey(key)) return null;
            referenced.put(key, true);
            return values.get(key);
        }

        @Override
        public void put(K key, V value) {
            if (values.containsKey(key)) {
                values.put(key, value);
                referenced.put(key, true);
                return;
            }
            if (values.size() == capacity) {
                while (true) {
                    K front = queue.pollFirst();
                    if (referenced.get(front)) {   // second chance: clear and requeue
                        referenced.put(front, false);
                        queue.addLast(front);
                    } else {                        // victim
                        values.remove(front);
                        referenced.remove(front);
                        break;
                    }
                }
            }
            queue.addLast(key);
            values.put(key, value);
            referenced.put(key, false);
        }

        @Override
        public int size() {
            return values.size();
        }
    }
}