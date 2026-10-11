package datastructures.hashtable;

public interface HashTable<K, V> {
    V put(K key, V value); // null on fresh insert, previous value on replace
    V get(K key);
    V remove(K key);
    boolean containsKey(K key);
    int size();
    boolean isEmpty();
    void clear();
}
