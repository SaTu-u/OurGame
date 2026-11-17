package dev.claudebridge;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/** Append-only log of every API request. */
final class Audit {
    private final Path file;
    private final boolean enabled;
    private BufferedWriter writer;

    Audit(Path file, boolean enabled) {
        this.file = file;
        this.enabled = enabled;
    }

    synchronized void log(String remote, String method, String path, String detail) {
        if (!enabled) return;
        try {
            if (writer == null) {
                Files.createDirectories(file.getParent());
                writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            String line = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    + " " + remote + " " + method + " " + path
                    + (detail == null || detail.isEmpty() ? "" : " " + oneLine(detail));
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException ignored) {
            // Auditing must never break the request itself.
        }
    }

    synchronized void close() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
            }
            writer = null;
        }
    }

    private static String oneLine(String s) {
        String t = s.replace('\n', ' ').replace('\r', ' ');
        return t.length() > 500 ? t.substring(0, 500) + "..." : t;
    }
}
