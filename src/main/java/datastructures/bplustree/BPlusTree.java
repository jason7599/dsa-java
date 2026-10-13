package datastructures.bplustree;

import datastructures.sortedmultiset.SortedMultiset;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class BPlusTree<T extends Comparable<? super T>> implements SortedMultiset<T> {

    private static final int DEFAULT_ORDER = 5;
    private static final int MIN_ORDER = 3;

    /* package-private */ final int order;
    /* package-private */ final int minKeys;

    /* package-private */ Node<T> root;  // cannot be final, root starts as a leaf but can turn into internal
    private int size;

    //region Constructors
    public BPlusTree() {
        this(DEFAULT_ORDER);
    }

    public BPlusTree(int order) {
        if (order < MIN_ORDER) throw new IllegalArgumentException("order must be >= " + MIN_ORDER);
        this.order = order;
        minKeys = (order + 1) / 2 - 1;
        root = new LeafNode<>(order);
    }
    //endregion

    //region Public API
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
    //endregion

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

        //`i` has split. `i` is still there, but we need to make room for the next `right`
        // shift and insert the new separator and children
        insertAt(in.keys, i, s.sep, in.keyCount);
        insertAt(in.children, i + 1, s.right, in.keyCount + 1);
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
        insertAt(ln.keys, i, key, ln.keyCount);
        insertAt(ln.counts, i, 1, ln.keyCount);
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

    //region Remove Helpers

    private void remove(Node<T> n, T key, boolean all) {
        switch (n) {
            case InternalNode<T> in -> removeFromInternal(in, key, all);
            case LeafNode<T> ln -> removeFromLeaf(ln, key, all);
        }
    }

    private void removeFromInternal(InternalNode<T> in, T key, boolean all) {
        int i = in.upperBound(key);

        remove(in.children[i], key, all);

        if (in.children[i].keyCount < minKeys) {
            fixUnderflow(in, i);
        }
    }

    private void removeFromLeaf(LeafNode<T> ln, T key, boolean all) {
        int i = ln.lowerBound(key);
        if (!isExact(ln, i, key)) return;

        if (!all && ln.counts[i] > 1) {
            ln.counts[i]--;
            size--;
            return;
        }

        size -= ln.counts[i];

        removeAt(ln.keys, i, ln.keyCount);
        removeAt(ln.counts, i, ln.keyCount);
        ln.keyCount--;

        // Underfull check is done in the parent
    }

    /**
     * p.children[s] is underfull; repair using a sibling.
     * @param p: The parent
     * @param i: The child index that is underfull
     */
    private void fixUnderflow(InternalNode<T> p, int i) {
        if (i > 0 && p.children[i - 1].keyCount > minKeys) {
            // left sibling can spare one, steal from left.
            rotateRight(p, i - 1);
        } else if (i < p.keyCount && p.children[i + 1].keyCount > minKeys) {
            // steal from right sibling.
            rotateLeft(p, i);
        }
    }

    /**
     * Borrow one entry from the RIGHT sibling: children[s] (left) is underfull, children[s + 1] (right) can spare one.
     * @param parent the internal node whose children are being rebalanced
     * @param s index of the separator between them: children[s] | keys[s] | children[s+1]
     */
    private void rotateLeft(InternalNode<T> parent, int s) {
        switch (parent.children[s]) {
            case InternalNode<T> ignored -> rotateLeftInternal(parent, s);
            case LeafNode<T> ignored -> rotateLeftLeaf(parent, s);
        }
    }

    /**
     * Borrow one entry from the RIGHT sibling: children[s] (left) is underfull,
     * children[s+1] (right) can spare one. s = index of the separator between them.
     *
     * Internal version. Internal keys are signposts, so the key rotates THROUGH the parent:
     *
     *          [ … P … ]                        [ … R0 … ]
     *           /     \                          /      \
     *     [L0 …]       [R0 R1 …]     ->    [L0 … P]      [R1 …]
     *     /  …  \      /  |  …                 …  \       |  …
     *                 c                            c
     *
     * - P  (parent's separator) comes DOWN to the end of left
     * - R0 (right's first key) goes UP to replace it
     * - c  (right's first child) crosses over to become left's last child
     */
    private void rotateLeftInternal(InternalNode<T> parent, int s) {
        InternalNode<T> left = (InternalNode<T>) parent.children[s];
        InternalNode<T> right = (InternalNode<T>) parent.children[s + 1];
        int lk = left.keyCount;
        int rk = right.keyCount;

        // Take right's first key and first child out
        T up = removeAt(right.keys, 0, rk);
        Node<T> child = removeAt(right.children, 0, rk + 1);
        right.keyCount--;

        // Left takes parent's separator
        left.keys[lk] = parent.keys[s];
        left.children[lk + 1] = child;
        left.keyCount++;

        // Right's old first key becomes the new separator
        parent.keys[s] = up;
    }

    /**
     * Borrow one entry from the RIGHT sibling: children[s] (left) is underfull,
     * children[s+1] (right) can spare one. s = index of the separator between them.
     *
     * Leaf version. Leaf keys are real elements, so they move SIDEWAYS (with their counts).
     * The parent's separator is only a copy, so it gets updated afterward:
     *
     *          [ … 40 … ]                     [ … 50 … ]
     *           /      \                       /      \
     *        [ ]       [40 50]      ->      [40]      [50]
     */
    private void rotateLeftLeaf(InternalNode<T> parent, int s) {
        LeafNode<T> left = (LeafNode<T>) parent.children[s];
        LeafNode<T> right = (LeafNode<T>) parent.children[s + 1];
        int lk = left.keyCount;
        int rk = right.keyCount;

        left.keys[lk] = removeAt(right.keys, 0, rk);
        left.counts[lk] = removeAt(right.counts, 0, rk);
        left.keyCount++;
        right.keyCount--;

        parent.keys[s] = right.keys[0];
    }

    /**
     * Borrow one entry from the LEFT sibling: children[s+1] (right) is underfull, children[s] (left) can spare one.
     * @param parent the internal node whose children are being rebalanced
     * @param s index of the separator between them: children[s] | keys[s] | children[s+1]
     */
    private void rotateRight(InternalNode<T> parent, int s) {
        switch (parent.children[s]) {
            case InternalNode<T> ignored -> rotateRightInternal(parent, s);
            case LeafNode<T> ignored -> rotateRightLeaf(parent, s);
        }
    }

    /**
     * Borrow one entry from the LEFT sibling: children[s+1] (right) is underfull,
     * children[s] (left) can spare one. s = index of the separator between them.
     *
     * Internal version. Internal keys are signposts, so the key rotates THROUGH the parent:
     *
     *            [ … P … ]                          [ … L2 … ]
     *             /     \                            /      \
     *     [… L1 L2]      [R0 …]        →      [… L1]         [P R0 …]
     *        …     \     /  …                    …           /   …
     *               c                                       c
     *
     * - L2 (left's last key) goes UP to replace P
     * - P  (parent's separator) comes DOWN to the front of right
     * - c  (left's last child) crosses over to become right's first child
     */
    private void rotateRightInternal(InternalNode<T> parent, int s) {
        InternalNode<T> left = (InternalNode<T>) parent.children[s];
        InternalNode<T> right = (InternalNode<T>) parent.children[s + 1];
        int lk = left.keyCount;
        int rk = right.keyCount;

        T up = removeAt(left.keys, lk - 1, lk);
        Node<T> child = removeAt(left.children, lk, lk + 1);
        left.keyCount--;

        insertAt(right.keys, 0, parent.keys[s], rk);
        insertAt(right.children, 0, child, rk + 1);
        right.keyCount++;

        parent.keys[s] = up;
    }

    /**
     * Borrow one entry from the LEFT sibling: children[s+1] (right) is underfull,
     * children[s] (left) can spare one. s = index of the separator between them.
     *
     * Leaf version. Leaf keys are real elements, so they move SIDEWAYS (with their counts).
     * The parent's separator is only a copy, so it gets re-derived afterward:
     *
     *            [ … 30 … ]                         [ … 20 … ]
     *             /      \                           /      \
     *        [10 20]      [ ]          →          [10]      [20]
     *
     * - 20 (left's last element, key + count as a pair) moves to the FRONT of right
     * - separator = right's NEW first key, which is exactly the element that just arrived
     */
    private void rotateRightLeaf(InternalNode<T> parent, int s) {
        LeafNode<T> left = (LeafNode<T>) parent.children[s];
        LeafNode<T> right = (LeafNode<T>) parent.children[s + 1];
        int lk = left.keyCount;
        int rk = right.keyCount;

        insertAt(right.keys, 0, removeAt(left.keys, lk - 1, lk), rk);
        insertAt(right.counts, 0, removeAt(left.counts, lk - 1, lk), rk);
        left.keyCount--;
        right.keyCount++;

        parent.keys[s] = right.keys[0];
    }

    //endregion

    //region Array helpers
    // Provide separate int[] version for counts

    // Inserts x at arr[i], shifting arr[i..n) right by one. arr must have room for n + 1.
    private static <E> void insertAt(E[] arr, int i, E x, int n) {
        System.arraycopy(arr, i, arr, i + 1, n - i);
        arr[i] = x;
    }

    private static void insertAt(int[] arr, int i, int x, int n) {
        System.arraycopy(arr, i, arr, i + 1, n - i);
        arr[i] = x;
    }

    private static <E> E removeAt(E[] arr, int i, int n) {
        E x = arr[i];
        System.arraycopy(arr, i + 1, arr, i, n - i - 1);
        arr[n - 1] = null;
        return x;
    }

    private static int removeAt(int[] arr, int i, int n) {
        int x = arr[i];
        System.arraycopy(arr, i + 1, arr, i, n - i - 1);
        arr[n - 1] = 0;
        return x;
    }

    //endregion

    //region Nodes
    /* package-private */
    abstract static sealed class Node<T> {
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

    /* package-private */
    final static class InternalNode<T extends Comparable<? super T>> extends Node<T> {
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

    /* package-private */
    final static class LeafNode<T extends Comparable<? super T>> extends Node<T> {
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
}