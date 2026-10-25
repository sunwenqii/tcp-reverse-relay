package dev.sunwenqi.tcpreverserelay.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * Save mapping and pause state together, preserving the other settings and comments.
 */
public final class ForwardFile {
    private ForwardFile() {
    }

    public static synchronized void save(Path path, List<String> entries) throws IOException {
        save(path, entries, null);
    }

    public static synchronized void save(Path path, List<String> entries, Set<Integer> disabled) throws IOException {
        String original = Files.readString(path, StandardCharsets.UTF_8);
        String[] lines = original.split("\\R", -1);
        int found = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().matches("forwards\\s*[=:].*")) {
                found++;
            }
        }
        if (found != 1) {
            throw new IOException("配置文件必须且只能有一行 forwards=，当前找到 " + found + " 行");
        }
        var values = new LinkedHashMap<String, String>();
        values.put("forwards", String.join(",", entries));
        if (disabled != null) values.put("disabled.forwards", String.join(",", PortRanges.compact(disabled)));
        ConfigFile.update(path, values);
    }
}
