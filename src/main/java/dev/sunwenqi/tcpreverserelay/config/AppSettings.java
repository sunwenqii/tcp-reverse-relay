package dev.sunwenqi.tcpreverserelay.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Read and validate the two local configuration files.
 */
public final class AppSettings {
    private AppSettings() {
    }

    public record Mapping(int remotePort, int localPort) {
    }

    public record Server(TunnelMode mode, int handshakePort, int adminPort, List<Integer> forwards,
                         SecurityConfig.Keys keys) {
    }

    public record Client(TunnelMode mode, String serverHost, int handshakePort, int adminPort,
                         List<Mapping> forwards, SecurityConfig.Keys keys) {
    }

    public static Server server(Path path) throws IOException {
        Properties p = read(path, Set.of("mode", "handshake.port", "admin.port", "admin.public", "forwards", "AES_KEY", "TOKEN_KEY", "ADMIN_TOKEN"));
        AdminSettingsFile.publicAccess(path);
        TunnelMode mode = mode(p);
        int handshake = port(p, "handshake.port");
        int admin = port(p, "admin.port");
        if (handshake == admin) {
            throw new IllegalArgumentException("handshake.port 与 admin.port 不能相同");
        }
        List<Integer> forwards = PortRanges.parse(p.getProperty("forwards", ""), handshake, admin);
        return new Server(mode, handshake, admin, forwards, keys(p));
    }

    public static Client client(Path path) throws IOException {
        Properties p = read(path, Set.of("mode", "server.host", "handshake.port", "admin.port", "admin.public", "forwards", "AES_KEY", "TOKEN_KEY", "ADMIN_TOKEN"));
        AdminSettingsFile.publicAccess(path);
        TunnelMode mode = mode(p);
        String host = required(p, "server.host");
        if (host.contains("/") || host.contains(":") || host.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("server.host 必须是 IPv4 地址或主机名");
        }
        int handshake = port(p, "handshake.port");
        int admin = port(p, "admin.port");
        List<Mapping> forwards = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (String part : entries(p.getProperty("forwards", ""))) {
            String[] pair = part.split(":", -1);
            if (pair.length != 2) {
                throw new IllegalArgumentException("客户端 forwards 应为 公网端口:本地端口，例如 18080:8080");
            }
            int remote = parsePort(pair[0], "forwards 公网端口");
            int local = parsePort(pair[1], "forwards 本地端口");
            if (local == admin) {
                throw new IllegalArgumentException("不能把本机管理端口 " + admin + " 作为转发目标");
            }
            if (!seen.add(remote)) {
                throw new IllegalArgumentException("客户端 forwards 中公网端口重复: " + remote);
            }
            forwards.add(new Mapping(remote, local));
        }
        return new Client(mode, host, handshake, admin, List.copyOf(forwards), keys(p));
    }

    private static SecurityConfig.Keys keys(Properties p) {
        return new SecurityConfig.Keys(p.getProperty("AES_KEY", "").trim(), p.getProperty("TOKEN_KEY", "").trim());
    }

    private static Properties read(Path path, Set<String> allowed) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("找不到配置文件 " + path.toAbsolutePath() + "；请确认 config 目录可读写，并从程序所在的工作目录启动");
        }
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            p.load(reader);
        }
        for (String key : p.stringPropertyNames()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("配置文件 " + path + " 存在未知配置项: " + key);
            }
        }
        if (!p.containsKey("forwards")) {
            throw new IllegalArgumentException("配置文件 " + path + " 缺少 forwards=；无映射时也请保留空行");
        }
        return p;
    }

    private static TunnelMode mode(Properties p) {
        return TunnelMode.parse(required(p, "mode"));
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少配置项 " + key);
        }
        return value.trim();
    }

    private static int port(Properties p, String key) {
        return parsePort(required(p, key), key);
    }

    public static int parsePort(String raw, String key) {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value >= 1 && value <= 65535) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // A clear error follows.
        }
        throw new IllegalArgumentException(key + " 必须是 1 至 65535 的端口号: " + raw);
    }

    private static List<String> entries(String value) {
        if (value.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String entry : value.split(",", -1)) {
            if (entry.isBlank()) {
                throw new IllegalArgumentException("forwards 中存在空映射，请检查多余的逗号");
            }
            result.add(entry.trim());
        }
        return result;
    }
}
