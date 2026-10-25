package dev.sunwenqi.tcpreverserelay.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        validatePassword(password);
        if (!AdminTokenFile.readInFile(file).isEmpty()) {
            throw new IllegalStateException("管理密码已经设置，请使用现有密码登录");
        }
        save(file, "ADMIN_TOKEN", password);
    }

    public static synchronized void changePassword(Path file, String current, String next) throws IOException {
        validatePassword(next);
        synchronized (ForwardFile.class) {
            String saved = AdminTokenFile.readInFile(file);
            if (!java.security.MessageDigest.isEqual(saved.getBytes(StandardCharsets.UTF_8), current.getBytes(StandardCharsets.UTF_8)))
                throw new IllegalArgumentException("当前密码与配置不一致，请刷新页面或重新启动程序后再试");
            if (saved.equals(next)) throw new IllegalArgumentException("新密码不能与当前密码相同");
            ConfigFile.update(file, java.util.Map.of("ADMIN_TOKEN", next));
        }
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 128
                || !password.equals(password.trim()) || password.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("管理密码需为 8 至 128 个字符，不能包含首尾空格或控制字符");
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
        ConfigFile.update(file, java.util.Map.of(key, value));
    }

    private static Pattern line(String key) {
        return Pattern.compile("(?m)^[ \\t]*" + Pattern.quote(key) + "[ \\t]*[=:]([^\\r\\n]*)");
    }
}
