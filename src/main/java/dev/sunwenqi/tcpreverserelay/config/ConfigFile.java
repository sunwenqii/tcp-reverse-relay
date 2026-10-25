package dev.sunwenqi.tcpreverserelay.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared atomic writer for mapping, identity and management settings. */
public final class ConfigFile {
    private ConfigFile() {}

    public static void update(Path file, Map<String, String> values) throws IOException {
        synchronized (ForwardFile.class) {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            for (var entry : values.entrySet()) {
                String value = entry.getValue();
                if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)
                    throw new IllegalArgumentException("配置值不能包含换行");
                Matcher matcher = Pattern.compile("(?m)^[ \\t]*" + Pattern.quote(entry.getKey())
                        + "[ \\t]*[=:][^\\r\\n]*").matcher(content);
                String line = entry.getKey() + "=" + value;
                if (matcher.find()) {
                    int start = matcher.start(), end = matcher.end();
                    if (matcher.find()) throw new IOException("配置文件只能有一行 " + entry.getKey());
                    content = content.substring(0, start) + line + content.substring(end);
                } else {
                    content += (content.isEmpty() || content.endsWith("\n") ? "" : System.lineSeparator())
                            + line + System.lineSeparator();
                }
            }
            Path absolute = file.toAbsolutePath();
            Path temporary = Files.createTempFile(absolute.getParent(), ".relay-config-", ".tmp");
            try {
                Files.writeString(temporary, content, StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }
}
