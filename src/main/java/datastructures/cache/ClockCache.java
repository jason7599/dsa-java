package datastructures.cache;

import datastructures.hashtable.ChainedHashTable;
import datastructures.hashtable.HashTable;

/**
 * A "second chance FIFO".
 * Imagine a FIFO queue where each entry has a "ref" bit. On eviction scan, if this bit is 1,
 * clear it and move it to the back of the queue, hence getting a second chance.
 * If it is 0, it gets evicted.
 * CLOCK does the same thing, except nothing moves. The entries stay in the fixed ring,
 * and the "hand" moves instead. The hand points the front of the queue.
 *
 * Implementation is beautifully simple...
 * No explicit sorting logic, no linked list pointer chasing,
 * just a circular walk where nothing moves.
 *
 * The whole point of is to "approximate" LRU with a single bit timestamp.
 * The `referenced` field denotes whether the entry was used since the hand last came by.
 *
 * Apparently this simple mechanism often beat LRU in real cases.
 * The idea being: real workloads have lots of "one-off" hits, and evicting them quickly mattered.
 * Whereas classic LRU has to waste cache space by carrying them all the way down the list.
 *
 * Tho ofc, this simplicity does come with the cost of precision.
 * Also can suffer from Belady's anomaly, where more capacity results in more misses.
 */
public class ClockCache<K, V> extends Cache<K, V> {

    private static class Entry<K, V> {
        final K key;
        V value;
        boolean referenced;

        Entry(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private final Entry<K, V>[] entries;
    private int hand;
    private int size;

    private final HashTable<K, Integer> indexMap = new ChainedHashTable<>();

    @SuppressWarnings("unchecked")
    public ClockCache(int capacity) {
        super(capacity);
        entries = (Entry<K, V>[]) new Entry[capacity];
    }

    @Override
    public V get(K key) {
        Integer index = indexMap.get(key);
        if (index == null) return null; // miss

        Entry<K, V> entry = entries[index];
        entry.referenced = true;
        return entry.value;
    }

    @Override
    public void put(K key, V value) {
        Integer index = indexMap.get(key);
        if (index != null) {
            Entry<K, V> entry = entries[index];
            entry.value = value;
            entry.referenced = true;
            return;
        }

        // not full yet
        if (size < capacity) {
            indexMap.put(key, size);
            entries[size++] = new Entry<>(key, value);
            // optional design choice to consider: set ref = 1 on insert
            // this gives every entry a free second chance
            return;
        }

        // full. find an entry that wasn't referenced.
        // Even if every entry was referenced, the loop always terminates
        // after one full lap, because all the ref bits are cleared on the way
        while (entries[hand].referenced) {
            entries[hand].referenced = false;   // second chance
            hand = (hand + 1) % capacity;       // wrap around
        }

        // hand now on victim.
        indexMap.remove(entries[hand].key);
        indexMap.put(key, hand);
        entries[hand] = new Entry<>(key, value);
        hand = (hand + 1) % capacity;
    }

    @Override
    public int size() {
        return size;
    }
}
