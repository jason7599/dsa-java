package storage.lsm;

import java.io.IOException;
import java.nio.file.Path;

/**
 * An immutable, sorted file of entries on disk.
 */
public class SSTable implements AutoCloseable {

    private final Path path;

    private SSTable(Path path) {
        this.path = path;
    }

    @Override
    public void close() throws IOException {

    }
}
