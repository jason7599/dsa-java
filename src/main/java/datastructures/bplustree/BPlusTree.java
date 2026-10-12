package datastructures.bplustree;

import datastructures.sortedmultiset.SortedMultiset;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class BPlusTree<T extends Comparable<? super T>> implements SortedMultiset<T> {

    //region Nodes
    /* package-private */ abstract static sealed class Node<T> {
        final T[] keys; // sorted; work as separators in internal, elements in leaf.
        int keyCount;   // number of keys in use.

        @SuppressWarnings("unchecked")
        Node(int order) {
            // Order = m means at most m children,
            // so technically keys can be at most m - 1.
            // However, leaving one extra room allows the "insert then split" trick which is nice
            keys = (T[]) new Comparable[order];
        }

        @Override
        public String toString() {
            return IntStream.range(0, keyCount)
                    .mapToObj(i -> String.valueOf(keys[i]))
                    .collect(Collectors.joining(", ", "[", "]"));
        }
    }

    /* package-private */ final static class InternalNode<T extends Comparable<? super T>> extends Node<T> {
        final Node<T>[] children;

        @SuppressWarnings("unchecked")
        InternalNode(int order) {
            super(order);
            // order = max children count, but + 1 for the overflow slot as above
            children = new Node[order + 1];
        }

        // find first index where keys[index] > key
        int upperBound(T key) {
            int i = 0;
            while (i < keyCount && key.compareTo(keys[i]) >= 0) {
                i++;
            }
            return i;
        }
    }

    /* package-private */ final static class LeafNode<T extends Comparable<? super T>> extends Node<T> {
        final int[] counts; // counts[i] = copies of keys[i], since we handle duplicates
        LeafNode<T> prev;   // doubly linked leaf chain, nullable
        LeafNode<T> next;

        LeafNode(int order) {
            super(order);
            counts = new int[order];
        }

        // Find first index where keys[index] >= key, or size if none (= where key should be inserted).
        int lowerBound(T key) {
            int i = 0;
            while (i < keyCount && key.compareTo(keys[i]) > 0) {
                i++;
            }
            return i;
        }
    }

    //endregion

    private static final int DEFAULT_ORDER = 5;
    private static final int MIN_ORDER = 3;
    /* package-private */ final int order;

    /* package-private */ Node<T> root;  // cannot be final, root starts as a leaf but can turn into internal
    private int size;

    //region Search helpers

    // this can never return null.
    private LeafNode<T> findLeaf(T key) {
        Node<T> n = root;
        while (n instanceof InternalNode<T> in) {
            n = in.children[in.upperBound(key)];
        }
        return (LeafNode<T>) n;
    }

    private LeafNode<T> leftmostLeaf() {
        Node<T> n = root;
        while (n instanceof InternalNode<T> in) {
            n = in.children[0];
        }
        return (LeafNode<T>) n;
    }

    private LeafNode<T> rightmostLeaf() {
        Node<T> n = root;
        while (n instanceof InternalNode<T> in) {
            n = in.children[in.keyCount];
        }
        return (LeafNode<T>) n;
    }

    private boolean isExact(LeafNode<T> ln, int i, T key) {
        return i < ln.keyCount && ln.keys[i].compareTo(key) == 0;
    }

    // element just before position i
    private T before(LeafNode<T> ln, int i) {
        if (i == 0) {
            // Cross into the previous node in link
            LeafNode<T> prev = ln.prev;
            return prev == null ? null : prev.keys[prev.keyCount - 1];
        }
        return ln.keys[i - 1];
    }

    // element at i (cross into next leaf if i == size)
    private T atOrAfter(LeafNode<T> ln, int i) {
        if (i == ln.keyCount) {
            LeafNode<T> next = ln.next;
            return next == null ? null : next.keys[0];
        }
        return ln.keys[i];
    }

    //endregion

    //region Insertion helpers
    /**
     * Result of a split
     * sep: the separator(key) to add
     * right: the created right node
     */
    private record Split<T>(T sep, Node<T> right) {}

    private Split<T> insert(Node<T> n, T key) {
        return switch (n) {
            case InternalNode<T> in -> insertIntoInternal(in, key);
            case LeafNode<T> ln -> insertIntoLeaf(ln, key);
        };
    }

    private Split<T> insertIntoInternal(InternalNode<T> in, T key) {
        int i = in.upperBound(key); // child index
        Split<T> s = insert(in.children[i], key);

        // no restructuring, everyone is happy
        if (s == null) return null;

        /*
         * `i` has split. `i` is still there, but we need to make room for the next `right`
         */
        // make room for new separator
        System.arraycopy(in.keys, i, in.keys, i + 1, in.keyCount - i);
        in.keys[i] = s.sep;

        // children[0, i] are untouched, shift from i + 1
        System.arraycopy(in.children, i + 1, in.children, i + 2, in.keyCount - i);
        in.children[i + 1] = s.right;

        in.keyCount++;

        // happy
        if (in.keyCount < order) return null;

        /*
         * Again, split.
         * Very similar to leaf split.
         * But here, the separator is not kept in either left or right, but rather goes up
         */
        int n = in.keyCount;
        int mid = n / 2;

        T upKey = in.keys[mid];
        InternalNode<T> right = new InternalNode<>(order);

        System.arraycopy(in.keys, mid + 1, right.keys,0,n - mid - 1);
        System.arraycopy(in.children,mid + 1, right.children, 0,n - mid);
        Arrays.fill(in.keys, mid, n, null);
        Arrays.fill(in.children, mid + 1, n + 1, null);

        in.keyCount = mid;
        right.keyCount = n - mid - 1;

        return new Split<>(upKey, right);
    }

    private Split<T> insertIntoLeaf(LeafNode<T> ln, T key) {
        int i = ln.lowerBound(key); // where to be inserted
        if (isExact(ln, i, key)) {
            ln.counts[i]++;
            size++;
            return null;
        }

        // Shift to make room for new key
        System.arraycopy(ln.keys, i, ln.keys, i + 1, ln.keyCount - i);
        System.arraycopy(ln.counts, i, ln.counts, i + 1, ln.keyCount - i);

        ln.keys[i] = key;
        ln.counts[i] = 1;
        ln.keyCount++;
        size++;

        if (ln.keyCount < order) return null;

        /*
         * Splitting process.
         * We spawn a new node, call it the right node.
         * This node (ln) becomes the left node.
         * Left node keeps [0, mid), and right node takes [mid, size)
         */
        int n = ln.keyCount;
        int mid = n / 2;

        LeafNode<T> right = new LeafNode<>(order);

        System.arraycopy(ln.keys, mid, right.keys, 0, n - mid);
        System.arraycopy(ln.counts, mid, right.counts, 0, n - mid);
        Arrays.fill(ln.keys, mid, n, null);
        Arrays.fill(ln.counts, mid, n, 0);

        ln.keyCount = mid;
        right.keyCount = n - mid;

        right.next = ln.next;
        if (ln.next != null) ln.next.prev = right;
        ln.next = right;
        right.prev = ln;

        return new Split<>(right.keys[0], right);
    }
    //endregion

    public BPlusTree() {
        this(DEFAULT_ORDER);
    }

    public BPlusTree(int order) {
        if (order < MIN_ORDER) throw new IllegalArgumentException("order must be >= " + MIN_ORDER);
        this.order = order;
        root = new LeafNode<>(order);
    }

    @Override
    public void insert(T data) {
        Split<T> s = insert(root, data);
        if (s != null) {
            // root has split
            InternalNode<T> newRoot = new InternalNode<>(order);
            newRoot.keyCount = 1;
            newRoot.keys[0] = s.sep;
            newRoot.children[0] = root;
            newRoot.children[1] = s.right;
            root = newRoot;
        }
    }

    @Override
    public boolean contains(T data) {
        // Can this be optimized?
        // As of right now this goes down the entire tree.
        // Maybe we can find the key in the internal nodes too?
        return count(data) != 0;
    }

    @Override
    public int count(T data) {
        LeafNode<T> ln = findLeaf(data);
        int lb = ln.lowerBound(data);
        return isExact(ln, lb, data) ? ln.counts[lb] : 0;
    }

    @Override
    public void remove(T data) {

    }

    @Override
    public void removeAll(T data) {

    }

    @Override
    public void clear() {
        root = new LeafNode<>(order);
        size = 0;
    }

    @Override
    public T min() {
        return leftmostLeaf().keys[0];
    }

    @Override
    public T max() {
        LeafNode<T> ln = rightmostLeaf();
        return ln.keys[ln.keyCount - 1];
    }

    @Override
    public T floor(T data) {
        LeafNode<T> ln = findLeaf(data);
        int i = ln.lowerBound(data);
        return isExact(ln, i, data) ? ln.keys[i] : before(ln, i);
    }

    @Override
    public T ceiling(T data) {
        LeafNode<T> ln = findLeaf(data);
        int i = ln.lowerBound(data);
        return atOrAfter(ln, i);
    }

    @Override
    public T lower(T data) {
        LeafNode<T> ln = findLeaf(data);
        int i = ln.lowerBound(data);
        return before(ln, i);
    }

    @Override
    public T higher(T data) {
        LeafNode<T> ln = findLeaf(data);
        int i = ln.lowerBound(data);
        if (isExact(ln, i, data)) i++;
        return atOrAfter(ln, i);
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public Iterator<T> iterator() {
        return new Iterator<>() {
            private LeafNode<T> current = leftmostLeaf();
            private int index;
            private int remaining = current == null ? 0 : current.counts[0];

            @Override
            public boolean hasNext() {
                return current != null;
            }

            @Override
            public T next() {
                if (!hasNext()) throw new NoSuchElementException();

                T data = current.keys[index];

                if (--remaining == 0) {
                    if (++index == current.keyCount) {
                        current = current.next;
                        index = 0;
                        remaining = current == null ? 0 : current.counts[0];
                    }
                }

                return data;
            }
        };
    }
}
