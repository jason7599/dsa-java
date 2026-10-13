package storage.lsm;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * An immutable, sorted file of entries on disk.
 *
 * Created either by flushing a memtable or merging existing SSTables during compaction.
 *
 * A single SSTable is not a representation of the whole database,
 * but rather a chunk of the database's history. Multiple SSTables may contain the same key,
 * so newer tables take precedence.
 *
 * On reads, SSTables are searched newest first if the memtable misses.
 * Tombstones count as entries too so that deleted keys don't come back from older SSTables.
 *
 * The SSTable itself does NOT hold the actual entries. That would defeat the whole purpose of LSM, wouldn't it?
 * Instead, only when get is called does it read the file and search for the entry.
 * TODO: Which is why we should also add something like a bloom filter here so that we can avoid unnecessary disk reads.
 */
class SSTable implements AutoCloseable, Iterable<Entry> {

    private final Path path;
    private final FileChannel channel;
    private final List<BlockIndexEntry> index;

    private static final int BLOCK_SIZE = 1 << 12; // 4kb


    private SSTable(Path path, List<BlockIndexEntry> index) throws IOException {
        this.path = path;
        channel = FileChannel.open(
                path,
                StandardOpenOption.READ // Read only.
        );
        this.index = index;
    }

    /**
     * Write a new SSTable from sorted entries.
     * First writes to a tmp file, then renames the file.
     * This is to ensure no .sst files are left torn/incomplete in case of crashes.
     * The tmp file itself is not important, because the source data
     * still exists elsewhere (the WAL, or the compaction inputs) until the rename completes
     */
    static SSTable create(Path path, Iterator<Entry> entries) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        List<BlockIndexEntry> index = new ArrayList<>();

        try {
            try (FileChannel channel = FileChannel.open(
                    tmp,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING // in case of duplicate tmp files
            )) {
                String blockFirstKey = null;
                long blockOffset = 0;
                int blockBytes = 0;

                while (entries.hasNext()) {
                    Entry entry = entries.next();
                    int entrySize = entry.encodedSize();

                    // Does this entry require a new block?
                    if (blockBytes > 0 && blockBytes + entrySize > BLOCK_SIZE) {
                        index.add(new BlockIndexEntry(
                                blockFirstKey,
                                blockOffset,
                                blockBytes
                        ));

                        blockFirstKey = null;
                        blockBytes = 0;
                    }

                    // first key of a new block
                    if (blockFirstKey == null) {
                        blockFirstKey = entry.key();
                        blockOffset = channel.position();
                    }

                    ByteBuffer buf = entry.serialize();

                    while (buf.hasRemaining()) {
                        if (channel.write(buf) == 0) {
                            throw new IOException("No progress writing SSTable entry");
                        }
                    }

                    blockBytes += entrySize;
                }

                if (blockBytes > 0) {
                    index.add(new BlockIndexEntry(
                            blockFirstKey,
                            blockOffset,
                            blockBytes
                    ));
                }

//                int

                // serialize block index


                // tmp data is now on disk
                channel.force(true);
            }

            // Publish the completed SSTable
            // Requests an atomic filesystem rename
            Files.move(
                    tmp,
                    path,
                    StandardCopyOption.ATOMIC_MOVE
            );

            return new SSTable(path, index);

        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    Entry get(String key) {
        return null;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    @Override
    public Iterator<Entry> iterator() {
        return null;
    }
}
