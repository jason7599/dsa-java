package storage.lsm;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * One key-value record. Tombstone has value == null, which marks a deletion.
 * Serialized format: [tombstone flag][key length][key][value length][value][crc32]
 * - tombstone flag: 0 = PUT, 1 = DELETE, 1 byte
 * - key length: byte length of key, 4 byte int
 * - key: raw key bytes
 * - value length: byte length of value, 4 byte int
 * - value: raw value bytes
 * - crc32: CRC Checksum, 4 byte int
 */
final class Entry implements Comparable<Entry> {

    private final String key;
    private final String value;     // null = tombstone
    private final int encodedSize;  // precomputed once and cached

    private static final int MIN_ENTRY_SIZE = 13;

    Entry(String key, String value) {
        this.key = Objects.requireNonNull(key);
        this.value = value;
        encodedSize
                = 1     // tombstone
                + 4 + key.getBytes(StandardCharsets.UTF_8).length
                + 4 + (value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length)
                + 4;    // crc
    }

    String key() { return key; }
    String value() { return value; }
    boolean isTombstone() { return value == null; }
    int encodedSize() { return encodedSize; }

    ByteBuffer serialize() {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = isTombstone()
                ? new byte[0]
                : value.getBytes(StandardCharsets.UTF_8);

        // data + 4 byte checksum
        ByteBuffer buf = ByteBuffer.allocate(encodedSize);

        buf.put((byte) (isTombstone() ? 1 : 0));
        buf.putInt(keyBytes.length);
        buf.put(keyBytes);
        buf.putInt(valueBytes.length);
        buf.put(valueBytes);

        final int dataSize = encodedSize - 4; // minus crc checksum

        // CRC checksum over the entire data
        CRC32 crc = new CRC32();
        crc.update(buf.array(), 0, dataSize);
        buf.putInt((int) crc.getValue());

        buf.flip(); // ready to be read
        return buf;
    }

    static Entry deserialize(ByteBuffer buf) {
        Objects.requireNonNull(buf);

        final int start = buf.position();

        if (buf.remaining() < MIN_ENTRY_SIZE) {
            throw new CorruptEntryException("Truncated entry header");
        }

        byte flag = buf.get();
        if (flag != 0 & flag != 1) {
            throw new CorruptEntryException("Invalid tombstone flag: " + flag);
        }

        boolean tombstone = flag == 1;

        int keyLength = buf.getInt();
        if (keyLength < 0 || keyLength > buf.remaining()) {
            throw new CorruptEntryException("bad key length: " + keyLength);
        }
        byte[] keyBytes = new byte[keyLength];
        buf.get(keyBytes);

        int valueLength = buf.getInt();
        if (valueLength < 0 || valueLength > buf.remaining()) {
            throw new CorruptEntryException("bad value length: " + valueLength);
        }
        byte[] valueBytes = new byte[valueLength];
        buf.get(valueBytes);

        // end of data, before checksum
        int end = buf.position();
        int checksum = buf.getInt();

        CRC32 crc = new CRC32();
        crc.update(buf.duplicate().position(start).limit(end));

        if ((int) crc.getValue() != checksum) {
            throw new CorruptEntryException("WAL checksum mismatch");
        }

        String key = new String(keyBytes, StandardCharsets.UTF_8);
        String value = tombstone ? null : new String(valueBytes, StandardCharsets.UTF_8);

        return new Entry(key, value);
    }

    @Override
    public int compareTo(Entry other) {
        return key.compareTo(other.key);
    }

    @Override
    public String toString() {
        return isTombstone()
                ? "DELETE " + key
                : "PUT " + key + " = " + value;
    }
}
