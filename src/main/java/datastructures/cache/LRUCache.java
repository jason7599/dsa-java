package datastructures.cache;

import datastructures.hashtable.ChainedHashTable;
import datastructures.hashtable.HashTable;

/**
 * A doubly linked list, but with a hash map pointing where each node is.
 * From the core perspective, a cache is much like a hash table, but
 * one with a fixed capacity and a policy on who to evict once full.
 */
public class LRUCache<K, V> extends Cache<K, V> {

    private static class Node<K, V> {
        final K key;    // needed to remove from the hash table on eviction
        V value;        // updated by put() on existing key

        // doubly linked list
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    // sentinel.
    // Kept circular. first = head.next, last = head.prev.
    // empty cache => head.next == head.prev == head
    // This pattern allows all nodes to have non-null neighbors!
    private final Node<K, V> head;

    // my own implementation :)
    private final HashTable<K, Node<K, V>> hashTable = new ChainedHashTable<>();

    public LRUCache(int capacity) {
        super(capacity);
        head = new Node<>(null, null);
        head.next = head.prev = head;
    }

    private void unlink(Node<K, V> n) {
        n.prev.next = n.next;
        n.next.prev = n.prev;
    }

    private void pushFront(Node<K, V> n) {
        n.prev = head;
        n.next = head.next;
        head.next.prev = n;
        head.next = n;
    }

    // null on miss; a hit may update the eviction policy’s bookkeeping
    @Override
    public V get(K key) {
        Node<K, V> n = hashTable.get(key);

        if (n == null) return null;

        unlink(n);
        pushFront(n);

        return n.value;
    }

    @Override
    public void put(K key, V value) {
        Node<K, V> existing = hashTable.get(key);
        // update, and move front
        if (existing != null) {
            existing.value = value;
            unlink(existing);
            pushFront(existing);
            return;
        }

        // evict oldest
        if (hashTable.size() == capacity) {
            Node<K, V> oldest = head.prev;
            unlink(oldest);
            hashTable.remove(oldest.key);
        }

        Node<K, V> n = new Node<>(key, value);
        hashTable.put(key, n);
        pushFront(n);
    }

    @Override
    public int size() {
        return hashTable.size();
    }
}
