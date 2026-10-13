package storage.lsm;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * Newest slice of the database, kept in memory.
 * Every write lands here (right after the WAL), and every read checks here first.
 * Still have to use tombstones instead of real deletes though, because it needs to be
 * able to distinguish whether a key had a value update or if it were removed.
 * When it gets big enough, it's written out as an SSTable.
 *
 * Basically a sorted map from key to its latest Entry.
 *
 * API:
 * - put: insert or replace. Only the newest version matters here, the full history lives in WAL.
 * - get: look up the key.
 * - approximateSizeBytes: to tell LSMTree when it's time to flush.
 * - freeze: make it read-only before flushing.
 */
public class MemTable implements Iterable<Entry> {

    // TODO: Would love to plug my own SkipList or any other SortedMultiset impls,
    // TODO: but sadly as of right now they don't provide a replace API which is required
    // With Map<String, String>, map.get(key) being null wont be able to distinguish absent vs tombstone.
    private final Map<String, Entry> map = new TreeMap<>();

    private long sizeBytes;
    private boolean frozen;

    public void put(Entry entry) {
        if (frozen) throw new IllegalStateException("memtable is frozen");

        Entry prev = map.put(entry.key(), entry);
        sizeBytes += entry.encodedSize();
        if (prev != null) {
            sizeBytes -= prev.encodedSize();
        }
    }

    // returns null if absent
    // if tombstone, .value() == null
    public Entry get(String key) {
        return map.get(key);
    }

    public void freeze() {
        frozen = true;
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    @Override
    public Iterator<Entry> iterator() {
        return Collections.unmodifiableCollection(map.values()).iterator();
    }
}
