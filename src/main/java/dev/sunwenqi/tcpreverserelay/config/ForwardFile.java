package dev.sunwenqi.tcpreverserelay.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Replace only the forwards line, preserving the other settings and comments.
 */
public final class ForwardFile {
    private ForwardFile() {
    }

    public static synchronized void save(Path path, List<String> entries) throws IOException {
        String original = Files.readString(path, StandardCharsets.UTF_8);
        String[] lines = original.split("\\R", -1);
        int found = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().matches("forwards\\s*[=:].*")) {
                lines[i] = "forwards=" + String.join(",", entries);
                found++;
            }
        }
        if (found != 1) {
            throw new IOException("配置文件必须且只能有一行 forwards=，当前找到 " + found + " 行");
        }
        Path absolute = path.toAbsolutePath();
        Path temp = Files.createTempFile(absolute.getParent(), ".forwards-", ".tmp");
        try {
            Files.writeString(temp, String.join(System.lineSeparator(), lines), StandardCharsets.UTF_8);
            try {
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
