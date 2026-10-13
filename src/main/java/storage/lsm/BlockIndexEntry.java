package storage.lsm;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Describes the location of a data block within an SSTable.
 *
 * Each index entry stores the first (smallest) key in a block,
 * the block's byte offset within the SSTable file, and its serialized length in bytes.
 *
 * Serialized format: [key length][first key][block offset][block length]
 * - key length: 4 byte int
 * - key: raw key bytes
 * - block offset: 8 byte long
 * - block length: 4 byte int
 */
record BlockIndexEntry(
        String firstKey,
        long offset,
        int length
) {
    private static final int MIN_SIZE = 16;

    BlockIndexEntry {
        Objects.requireNonNull(firstKey);
        if (offset < 0) throw new IllegalArgumentException("offset cannot be negative");
        if (length <= 0) throw new IllegalArgumentException("length has to be positive");
    }

    ByteBuffer serialize() {
        byte[] keyBytes = firstKey.getBytes(StandardCharsets.UTF_8);

        final int encodedSize = MIN_SIZE + keyBytes.length;

        ByteBuffer buf = ByteBuffer.allocate(encodedSize);

        buf.putInt(keyBytes.length);
        buf.put(keyBytes);
        buf.putLong(offset);
        buf.putInt(length);

        buf.flip();
        return buf;
    }

    static BlockIndexEntry deserialize(ByteBuffer buf) {
        Objects.requireNonNull(buf);

        ByteBuffer cursor = buf.duplicate();

        if (cursor.remaining() < MIN_SIZE) {
            throw new IllegalArgumentException("Truncated block index entry");
        }

        int keyLength = cursor.getInt();
        if (keyLength < 0 || keyLength > cursor.remaining()) {
            throw new IllegalArgumentException("Invalid block index key length: " + keyLength);
        }

        byte[] keyBytes = new byte[keyLength];
        cursor.get(keyBytes);

        String firstKey;
        try {
            firstKey = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(keyBytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid UTF-8 in block index key", e);
        }

        long offset = cursor.getLong();
        int length = cursor.getInt();

        BlockIndexEntry entry = new BlockIndexEntry(firstKey, offset, length);

        buf.position(cursor.position());

        return entry;
    }
}