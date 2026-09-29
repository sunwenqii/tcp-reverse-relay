package dev.sunwenqi.tcpreverserelay.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the local management password in the editable configuration file.
 */
public final class AdminTokenFile {
    private static final Pattern LINE = Pattern.compile("(?m)^[ \\t]*ADMIN_TOKEN[ \\t]*[=:].*$");

    private AdminTokenFile() {
    }

    public static String resolve(Path file) throws IOException {
        String fromEnvironment = System.getenv("TCP_REVERSE_RELAY_ADMIN_TOKEN");
        return fromEnvironment == null || fromEnvironment.isBlank() ? readInFile(file) : fromEnvironment;
    }

    public static String readInFile(Path file) throws IOException {
        String original = Files.readString(file, StandardCharsets.UTF_8);
        Matcher matcher = LINE.matcher(original);
        String value = "";
        boolean found = false;
        while (matcher.find()) {
            if (found) throw new IOException("配置文件只能有一行 ADMIN_TOKEN: " + file);
            found = true;
            value = matcher.group().replaceFirst("^[ \\t]*ADMIN_TOKEN[ \\t]*[=:]", "").trim();
        }
        return value;
    }
}
