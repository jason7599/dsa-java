package datastructures.hashtable;

/**
 * A hash table implementation using separate chaining for collision resolution.
 * Each bucket stores a linked list of entries. The table automatically resizes
 * when adding an entry would exceed the configured maximum load factor.
 */
public class ChainedHashTable<K, V> implements HashTable<K, V> {

    private static class Entry<K, V> {
        final K key;        // key is stored as well in case of collisions
        V value;
        Entry<K, V> next;   // linked list for collisions

        Entry(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private static final int DEFAULT_INIT_CAPACITY = 16;    // capacity = number of buckets
    private static final double MAX_LOAD_FACTOR = 0.75;     // if load factor (size / capacity) exceeds this value, resize

    private Entry<K, V>[] buckets;
    private int size; // number of total entries

    @SuppressWarnings("unchecked")
    private void allocateBuckets(int capacity) {
        buckets = (Entry<K, V>[]) new Entry[capacity];
    }

    public ChainedHashTable() {
        allocateBuckets(DEFAULT_INIT_CAPACITY);
    }

    private int bucketIndex(K key) {
        // instead of regular % because hashCode can be negative
        return Math.floorMod(key.hashCode(), buckets.length);
    }

    private Entry<K, V> getEntry(K key) {
        Entry<K, V> entry = buckets[bucketIndex(key)];
        while (entry != null) {
            if (entry.key.equals(key)) {
                return entry;
            }
            entry = entry.next;
        }

        return null;
    }

    // Double previous capacity, and reinsert old entries
    private void resize() {
        Entry<K, V>[] old = buckets;
        allocateBuckets(old.length * 2);

        for (Entry<K, V> entry : old) {
            while (entry != null) {
                Entry<K, V> next = entry.next;

                int index = bucketIndex(entry.key);
                entry.next = buckets[index];
                buckets[index] = entry;

                entry = next;
            }
        }
    }

    @Override
    public V put(K key, V value) {
        Entry<K, V> entry = getEntry(key);
        if (entry != null) {
            // replace existing
            V old = entry.value;
            entry.value = value;
            return old;
        }

        if ((double)(size + 1) / buckets.length > MAX_LOAD_FACTOR) {
            resize();
        }

        Entry<K, V> newEntry = new Entry<>(key, value);

        int index = bucketIndex(key);
        newEntry.next = buckets[index];
        buckets[index] = newEntry;
        size++;

        return null;
    }

    @Override
    public V get(K key) {
        Entry<K, V> entry = getEntry(key);

        // thought about throwing here, but that would kinda imply
        // the common flow being "check then get", which might be actual redundant work
        // if in most cases users are certain. given how I provide a containsKey method anyway,
        // I think it's better to let the user distinguish if needed.
        if (entry == null) {
            return null;
        }

        return entry.value;
    }

    @Override
    public V remove(K key) {
        int index = bucketIndex(key);

        Entry<K,V> prev = buckets[index];
        if (prev == null) {
            return null;
        }

        if (prev.key.equals(key)) {
            buckets[index] = prev.next;
            size--;
            return prev.value;
        }

        while (prev.next != null && !prev.next.key.equals(key)) {
            prev = prev.next;
        }

        if (prev.next == null) {
            return null;
        }

        V value = prev.next.value;
        prev.next = prev.next.next;
        size--;

        return value;
    }

    @Override
    public boolean containsKey(K key) {
        return getEntry(key) != null;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public void clear() {
        allocateBuckets(DEFAULT_INIT_CAPACITY);
        size = 0;
    }
}
