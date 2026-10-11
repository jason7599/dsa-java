package datastructures.hashtable;

/**
 * Hash table implementation using open addressing with linear probing
 * Core idea:
 * - Each key has a "home" index
 * - If that slot is occupied, probe one slot forward at a time until we find a usable slot (null slot)
 * This sequence of non-null slots is called a probe sequence.
 * Unlike separate chaining, entries live directly in the backing array, hence the name open addressing.
 * This gives good memory locality, but makes collision handling and deletion more complex.
 */
public class LinearProbingHashTable<K, V> implements HashTable<K, V> {

    private static class Entry<K, V> {
        K key;
        V value;
        boolean deleted;

        Entry(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private static final int DEFAULT_INIT_CAPACITY = 16;

    /*
     * Open addressing generally needs a lower maximum load factor than
     * separate chaining because probe lengths increase sharply as the
     * backing array becomes crowded.
     */
    private static final double MAX_LOAD_FACTOR = 0.6;

    private Entry<K, V>[] slots;

    private int size; // number of live entries
    private int used; // non-null slots, including tombstones

    @SuppressWarnings("unchecked")
    private void allocateSlots(int capacity) {
        slots = (Entry<K, V>[]) new Entry[capacity];
    }

    public LinearProbingHashTable() {
        allocateSlots(DEFAULT_INIT_CAPACITY);
    }

    private int homeIndex(K key) {
        return Math.floorMod(key.hashCode(), slots.length);
    }

    private Entry<K, V> getEntry(K key) {
        int home = homeIndex(key);
        for (int probe = 0; probe < slots.length; probe++) {
            int index = (home + probe) % slots.length;

            Entry<K, V> entry = slots[index];

            // null slot = safe to early terminate
            // This works because a slot never goes back to null once used:
            // removal leaves a tombstone, so the probe sequence is never broken
            // between a key's home index and where it actually lives
            if (entry == null) {
                return null;
            }

            if (!entry.deleted && entry.key.equals(key)) {
                return entry;
            }
        }

        // made full circle, not found
        return null;
    }

    private void rehash() {
        Entry<K, V>[] old = slots;

        // Grow only if the live entries need the room
        // If the table is mostly tombstones, rebuilding at
        // the same capacity is enough to clear them
        int newCapacity = ((double) (size + 1) / old.length > MAX_LOAD_FACTOR)
                ? old.length * 2
                : old.length;

        allocateSlots(newCapacity);
        used = size; // removing all tombstones

        for (Entry<K, V> entry : old) {
            if (entry != null && !entry.deleted) {
                int index = homeIndex(entry.key);
                // no risk of going around the whole table
                while (slots[index] != null) {
                    index = (index + 1) % slots.length;
                }
                slots[index] = entry;
            }
        }
    }

    @Override
    public V put(K key, V value) {

        // Done preemptively, even if key might already exist in which case
        // it is replaced and used is not incremented.
        // But this makes the code much simpler
        if ((double)(used + 1) / slots.length > MAX_LOAD_FACTOR) {
            rehash();
        }

        int home = homeIndex(key);
        int firstTombstone = -1; // the index of the first deleted entry in the sequence

        for (int probe = 0; probe < slots.length; probe++) {
            int index = (home + probe) % slots.length;
            Entry<K, V> entry = slots[index];

            // end of the chain: key is not present, so insert
            if (entry == null) {
                Entry<K, V> newEntry = new Entry<>(key, value);

                // reuse the earliest tombstone if exists
                if (firstTombstone != -1) {
                    slots[firstTombstone] = newEntry;
                } else {
                    slots[index] = newEntry;
                    used++;
                }
                size++;
                return null;
            }

            if (entry.deleted) {
                if (firstTombstone == -1) {
                    firstTombstone = index;
                }
            } else if (entry.key.equals(key)) {
                // replace
                V old = entry.value;
                entry.value = value;
                return old;
            }
        }

        // Unreachable; the load check guarantees at least one null slot
        throw new IllegalStateException("hash table is full");
    }

    @Override
    public V get(K key) {
        Entry<K, V> entry = getEntry(key);
        if (entry == null) {
            return null;
        }
        return entry.value;
    }

    @Override
    public V remove(K key) {
        Entry<K, V> entry = getEntry(key);
        if (entry == null) {
            return null;
        }

        V value = entry.value;
        entry.key = null;
        entry.deleted = true;
        entry.value = null;
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
        allocateSlots(DEFAULT_INIT_CAPACITY);
        size = 0;
        used = 0;
    }
}
