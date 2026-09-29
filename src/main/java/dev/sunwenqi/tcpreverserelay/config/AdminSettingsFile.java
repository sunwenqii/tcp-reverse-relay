package dev.sunwenqi.tcpreverserelay.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Persists management settings while preserving the other config lines.
 */
public final class AdminSettingsFile {
    private AdminSettingsFile() {
    }

    public static boolean publicAccess(Path file) throws IOException {
        String value = value(file, "admin.public");
        if (value.isEmpty() || value.equals("false")) return false;
        if (value.equals("true")) return true;
        throw new IllegalArgumentException("admin.public 只能是 true 或 false: " + value);
    }

    public static synchronized void setPassword(Path file, String password) throws IOException {
        if (password == null || password.length() < 8 || password.length() > 128
                || !password.equals(password.trim()) || password.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("管理密码需为 8 至 128 个字符，不能包含首尾空格或控制字符");
        }
        if (!AdminTokenFile.readInFile(file).isEmpty()) {
            throw new IllegalStateException("管理密码已经设置，请使用现有密码登录");
        }
        save(file, "ADMIN_TOKEN", password);
    }

    public static synchronized void setPublicAccess(Path file, boolean enabled) throws IOException {
        save(file, "admin.public", Boolean.toString(enabled));
    }

    private static String value(Path file, String key) throws IOException {
        Matcher matcher = line(key).matcher(Files.readString(file, StandardCharsets.UTF_8));
        String value = "";
        boolean found = false;
        while (matcher.find()) {
            if (found) throw new IOException("配置文件只能有一行 " + key + ": " + file);
            found = true;
            value = matcher.group(1).trim();
        }
        return value;
    }

    private static void save(Path file, String key, String value) throws IOException {
        synchronized (ForwardFile.class) {
            String original = Files.readString(file, StandardCharsets.UTF_8);
            Matcher matcher = line(key).matcher(original);
            if (matcher.find()) {
                int start = matcher.start();
                int end = matcher.end();
                if (matcher.find()) throw new IOException("配置文件只能有一行 " + key + ": " + file);
                original = original.substring(0, start) + key + "=" + value + original.substring(end);
            } else {
                original += (original.isEmpty() || original.endsWith("\n") ? "" : System.lineSeparator())
                        + key + "=" + value + System.lineSeparator();
            }
            Path absolute = file.toAbsolutePath();
            Path temporary = Files.createTempFile(absolute.getParent(), ".admin-settings-", ".tmp");
            try {
                Files.writeString(temporary, original, StandardCharsets.UTF_8);
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

    private static Pattern line(String key) {
        return Pattern.compile("(?m)^[ \\t]*" + Pattern.quote(key) + "[ \\t]*[=:]([^\\r\\n]*)");
    }
}
