package storage.lsm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Log-Structured Merge Tree
 *
 * A data structure designed to make database writes fast
 * Core idea:
 *  Instead of constantly writing data on disk, write new data sequentially and organize later.
 *
 * Structure:
 * - MemTable (RAM): Sorted, in-memory data.
 * - SSTable (Disk): Immutable sorted files.
 */
public class LSMTree implements KeyValueStore {

    private final Path dataDir;
    private final long memtableFlushThresholdBytes;
    private final int compactionThreshold;

    private MemTable memtable;
    private WriteAheadLog wal;
    private final List<SSTable> sstables = new ArrayList<>(); // newest first
    private long nextFileNumber; // shared single counter by both WALs and SSTables

    private LSMTree(Path dataDir, long memtableFlushThresholdBytes, int compactionThreshold) throws IOException {
        this.dataDir = dataDir;
        this.memtableFlushThresholdBytes = memtableFlushThresholdBytes;
        this.compactionThreshold = compactionThreshold;
    }

    public static LSMTree open(Path dataDir, long memtableFlushThresholdBytes, int compactionThreshold) throws IOException {
        LSMTree tree = new LSMTree(dataDir, memtableFlushThresholdBytes, compactionThreshold);

        Files.createDirectories(dataDir); // no-op if exists
        tree.memtable = new MemTable();

        List<Path> files;
        try (Stream<Path> stream = Files.list(dataDir)) {
            files = stream
                    .filter(path -> FileType.getType(path) != null)
                    .sorted(Comparator.comparingLong(FileType::parseNumber))
                    .toList();
        }

        List<Path> oldWals = new ArrayList<>();
        for (Path path : files) {
            switch (Objects.requireNonNull(FileType.getType(path))) {
                case WAL -> {
                    // Replay leftover WALs.
                    // Ideally there should be one or none, but a crash mid-flush could leave two WALs.
                    for (Entry e : WriteAheadLog.replay(path)) {
                        tree.memtable.put(e);
                    }
                    oldWals.add(path);
                }
                case SST -> {
                    //
                }
            }
        }

        // remove old wals after the flush
        for (Path wal : oldWals) {
            Files.delete(wal);
        }

        tree.nextFileNumber = files.isEmpty() ? 0 : FileType.parseNumber(files.getLast()) + 1;
        tree.wal = new WriteAheadLog(FileType.WAL.path(dataDir, tree.nextFileNumber++));

        return tree;
    }

    @Override
    public void put(String key, String value) throws IOException {
        Entry entry = new Entry(key, value);
        wal.append(entry);
        memtable.put(entry);
    }

    @Override
    public String get(String key) throws IOException {
        Entry entry = memtable.get(key);

        if (entry != null) return entry.value();


    }

    @Override
    public void delete(String key) throws IOException {
        put(key, null); // place tombstone
    }

    @Override
    public void close() throws IOException {
        wal.close();
    }
}
