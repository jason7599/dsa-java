package datastructures.cache;

public abstract class Cache<K, V> {

    protected final int capacity;

    public Cache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException();
        }
        this.capacity = capacity;
    }

    public abstract V get(K key); // null on miss
    public abstract void put(K key, V value);

    public abstract int size();

    public boolean isEmpty() {
        return size() == 0;
    }

    public int capacity() {
        return capacity;
    }
}
