package dev.sunwenqi.tcpreverserelay.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates editable configuration files from the defaults bundled with the application.
 */
public final class ConfigFiles {
    private ConfigFiles() {
    }

    public static Path server() throws IOException {
        return ensureIn(Path.of("config"), "server.properties");
    }

    public static Path client() throws IOException {
        return ensureIn(Path.of("config"), "client.properties");
    }

    static Path ensureIn(Path directory, String name) throws IOException {
        Path file = directory.toAbsolutePath().normalize().resolve(name);
        if (Files.exists(file)) {
            return regularFile(file);
        }

        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), name + ".", ".tmp");
        try {
            try (InputStream defaults = ConfigFiles.class.getResourceAsStream("/" + name)) {
                if (defaults == null) {
                    throw new IOException("程序缺少内置默认配置 " + name + "，请重新构建完整的 JAR");
                }
                Files.copy(defaults, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temporary, file);
            } catch (FileAlreadyExistsException ignored) {
                // Another process already created the file; its settings take precedence.
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return regularFile(file);
    }

    private static Path regularFile(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("配置路径不是普通文件: " + file);
        }
        return file;
    }
}
