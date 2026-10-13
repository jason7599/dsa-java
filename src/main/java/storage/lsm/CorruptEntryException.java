package storage.lsm;

public class CorruptEntryException extends RuntimeException {
    public CorruptEntryException(String message) {
        super(message);
    }
}
