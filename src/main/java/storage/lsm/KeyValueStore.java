package storage.lsm;

import java.io.IOException;

/**
 * A simple persistent key-value store.
 * Keys and values are Strings to keep serialization simple
 */
public interface KeyValueStore extends AutoCloseable {

    /** Insert or overwrite the value for a key */
    void put(String key, String value) throws IOException;

    /** Returns the current value or null if the key is absent or deleted */
    String get(String key) throws IOException;

    void delete(String key) throws IOException;

    /** Flush state and release file handles */
    @Override
    void close() throws IOException;
}
