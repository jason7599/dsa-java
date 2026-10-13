package storage.lsm;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only log protecting one memtable, for crash recovery. Each memtable gets its own WAL file.
 * Once the memtable is safely flushed to an SSTable, the WAL files is deleted/closed
 */
public class WriteAheadLog implements AutoCloseable {

    private final Path path;
    private final FileChannel channel;

    public WriteAheadLog(Path path) throws IOException {
        this.path = path;
        channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE, // create if absent
                StandardOpenOption.APPEND
        );
    }

    /** Append one entry, call before applying to memtable */
    public void append(Entry entry) throws IOException {
        ByteBuffer buf = entry.serialize();

        while (buf.hasRemaining()) {
            if (channel.write(buf) == 0) {
                throw new IOException("No progress writing WAL entry");
            }
        }

        // flush right away instead of keeping it in RAM
        channel.force(false);
    }

    /** Read from WAL file and return the entries in order. */
    public static List<Entry> replay(Path path) throws IOException {
        // a WAL should be bounded by memtable flush threshold so this shouldnt be too terrible
        ByteBuffer buf = ByteBuffer.wrap(Files.readAllBytes(path));
        List<Entry> entries = new ArrayList<>();

        while (buf.hasRemaining()) {
            try {
                Entry entry = Entry.deserialize(buf);
                entries.add(entry);
            } catch (CorruptEntryException e) {
                System.err.printf("WAL %s: stopping at offset %d, %d bytes unread (%s)%n",
                        path, buf.position(), buf.remaining(), e.getMessage());
                break;
            }
        }

        return entries;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    public void delete() throws IOException {
        close();
        Files.deleteIfExists(path);
    }
}
