package storage.lsm;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public enum FileType {

    WAL("wal"),
    SST("sst");

    private static final int DIGIT_SPACE = 12;
    private static final long MAX_NUMBER = Long.parseLong("9".repeat(DIGIT_SPACE));

    private final String format;
    private final Pattern pattern;

    FileType(String ext) {
        format = "%0" + DIGIT_SPACE + "d." + ext;
        pattern = Pattern.compile("(\\d{" + DIGIT_SPACE + "})\\." + Pattern.quote(ext));
    }

    public Path path(Path dir, long number) {
        if (number < 0 || number > MAX_NUMBER) {
            throw new IllegalArgumentException("File number must be between 0 and " + MAX_NUMBER + ": " + number);
        }
        return dir.resolve(format.formatted(number));
    }

    public static FileType getType(Path path) {
        String fileName = path.getFileName().toString();
        for (FileType type : FileType.values()) {
            if (type.pattern.matcher(fileName).matches()) {
                return type;
            }
        }
        return null;
    }

    public static long parseNumber(Path path) {
        String fileName = path.getFileName().toString();

        for (FileType type : values()) {
            Matcher matcher = type.pattern.matcher(fileName);
            if (matcher.matches()) {
                return Long.parseLong(matcher.group(1));
            }
        }

        throw new IllegalArgumentException(
                "Unrecognized filename: " + path
        );
    }
}