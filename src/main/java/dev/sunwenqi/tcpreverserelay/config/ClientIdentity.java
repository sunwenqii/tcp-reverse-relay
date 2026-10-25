package dev.sunwenqi.tcpreverserelay.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Properties;
import java.util.UUID;

public record ClientIdentity(String id, String name) {
    public static ClientIdentity resolve(Path file, Properties properties) throws IOException {
        String id = properties.getProperty("client.id", "").trim();
        String name = properties.getProperty("client.name", "").trim();
        var updates = new LinkedHashMap<String, String>();
        if (id.isEmpty()) { id = UUID.randomUUID().toString(); updates.put("client.id", id); }
        if (!id.matches("[A-Za-z0-9._-]{1,64}"))
            throw new IllegalArgumentException("client.id 只能包含 1 至 64 位字母、数字、点、下划线或短横线");
        if (name.isEmpty()) {
            name = System.getenv().getOrDefault("COMPUTERNAME", System.getenv().getOrDefault("HOSTNAME", "client"));
            if (name.length() > 64) name = name.substring(0, 64);
            updates.put("client.name", name.replace("\\", "\\\\"));
        }
        if (name.length() > 64 || name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("client.name 需为 1 至 64 个字符，不能包含控制字符");
        if (!updates.isEmpty()) ConfigFile.update(file, updates);
        return new ClientIdentity(id, name);
    }
}
