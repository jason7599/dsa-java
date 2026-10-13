package storage.lsm;

import datastructures.array.DynamicArray;
import datastructures.array.DynamicArrayImpl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
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
    private final DynamicArray<SSTable> sstables = new DynamicArrayImpl<>(); // newest first
    private long nextFileNumber; // shared single counter by both WALs and SSTables

    public LSMTree(Path dataDir, long memtableFlushThresholdBytes, int compactionThreshold) throws IOException {
        this.dataDir = dataDir;
        this.memtableFlushThresholdBytes = memtableFlushThresholdBytes;
        this.compactionThreshold = compactionThreshold;
        recover();
    }

    private void recover() throws IOException {
        Files.createDirectories(dataDir); // no-op if exists
        memtable = new MemTable();

        try (Stream<Path> stream = Files.list(dataDir)) {
            List<Path> files = stream
                    .filter(path -> FileType.getType(path) != null)
                    .sorted(Comparator.comparingLong(FileType::parseNumber))
                    .toList();

            for (Path path : files) {
                if (FileType.getType(path) == FileType.WAL) {
                    /*
                     * Replay leftover WALs.
                     * Ideally there should be one or none, but a crash mid-flush could leave two WALs.
                     */
                    for (Entry e : WriteAheadLog.replay(path)) {
                        memtable.put(e);
                    }
                } else if (FileType.getType(path) == FileType.SST) { // just for clarity

                }

                nextFileNumber = FileType.parseNumber(path) + 1;
            }
        }
    }

    @Override
    public void put(String key, String value) throws IOException {

    }

    @Override
    public String get(String key) throws IOException {
        return "";
    }

    @Override
    public void delete(String key) throws IOException {

    }

    @Override
    public void close() throws IOException {

    }
}
