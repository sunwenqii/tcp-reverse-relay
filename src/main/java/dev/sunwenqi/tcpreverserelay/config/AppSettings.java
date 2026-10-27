package dev.sunwenqi.tcpreverserelay.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import dev.sunwenqi.tcpreverserelay.health.HealthSettings;

/**
 * Read and validate the two local configuration files.
 */
public final class AppSettings {
    private AppSettings() {
    }

    public record Mapping(int remotePort, String targetHost, int localPort) {
        public Mapping(int remotePort, int localPort) { this(remotePort, "127.0.0.1", localPort); }
        public String configEntry() {
            return remotePort + ":" + (targetHost.equals("127.0.0.1") ? Integer.toString(localPort)
                    : TargetAddress.display(targetHost, localPort));
        }
    }

    public record Server(TunnelMode mode, int handshakePort, int adminPort, List<Integer> forwards,
                         SecurityConfig.Keys keys, Set<Integer> disabledForwards, Map<Integer, String> portOwners) {
        public Server(TunnelMode mode, int handshakePort, int adminPort, List<Integer> forwards, SecurityConfig.Keys keys, Set<Integer> disabled) {
            this(mode, handshakePort, adminPort, forwards, keys, disabled, Map.of());
        }
        public Server(TunnelMode mode, int handshakePort, int adminPort, List<Integer> forwards, SecurityConfig.Keys keys) {
            this(mode, handshakePort, adminPort, forwards, keys, Set.of(), Map.of());
        }
    }

    public record Client(TunnelMode mode, String serverHost, int handshakePort, int adminPort,
                         List<Mapping> forwards, SecurityConfig.Keys keys, String clientId, String clientName,
                         Set<Integer> disabledForwards, HealthSettings health) {
        public Client(TunnelMode mode, String host, int handshakePort, int adminPort, List<Mapping> forwards, SecurityConfig.Keys keys,
                      String id, String name, Set<Integer> disabled) {
            this(mode, host, handshakePort, adminPort, forwards, keys, id, name, disabled, HealthSettings.DEFAULT);
        }
        public Client(TunnelMode mode, String serverHost, int handshakePort, int adminPort, List<Mapping> forwards,
                      SecurityConfig.Keys keys) {
            this(mode, serverHost, handshakePort, adminPort, forwards, keys, UUID.randomUUID().toString(), "client", Set.of());
        }
    }

    public static Server server(Path path) throws IOException {
        Properties p = read(path, Set.of("mode", "handshake.port", "admin.port", "admin.public", "forwards", "disabled.forwards", "port.owners", "AES_KEY", "TOKEN_KEY", "ADMIN_TOKEN"));
        AdminSettingsFile.publicAccess(path);
        TunnelMode mode = mode(p);
        int handshake = port(p, "handshake.port");
        int admin = port(p, "admin.port");
        if (handshake == admin) {
            throw new IllegalArgumentException("handshake.port 与 admin.port 不能相同");
        }
        List<Integer> forwards = PortRanges.parse(p.getProperty("forwards", ""), handshake, admin);
        return new Server(mode, handshake, admin, forwards, keys(p), disabled(p, new HashSet<>(forwards)), PortOwners.parse(p.getProperty("port.owners", ""), forwards));
    }

    public static Client client(Path path) throws IOException {
        Properties p = read(path, Set.of("mode", "server.host", "handshake.port", "admin.port", "admin.public", "forwards", "disabled.forwards", "client.id", "client.name", "health.enabled", "health.interval.seconds", "health.timeout.millis", "health.failure.threshold", "AES_KEY", "TOKEN_KEY", "ADMIN_TOKEN"));
        AdminSettingsFile.publicAccess(path);
        TunnelMode mode = mode(p);
        var connection = new ServerConnection(required(p, "server.host"), port(p, "handshake.port"));
        String host = connection.host();
        int handshake = connection.port();
        int admin = port(p, "admin.port");
        connection.validateAdminPort(admin);
        List<Mapping> forwards = parseMappings(p.getProperty("forwards", ""), admin);
        Set<Integer> ports = new HashSet<>();
        forwards.forEach(mapping -> ports.add(mapping.remotePort()));
        Set<Integer> disabled = disabled(p, ports);
        ClientIdentity identity = ClientIdentity.resolve(path, p);
        return new Client(mode, host, handshake, admin, forwards, keys(p), identity.id(), identity.name(), disabled, HealthSettings.from(p));
    }

    public static List<Mapping> parseMappings(String value, int admin) {
        List<Mapping> result = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (String entry : entries(value.trim().replaceAll("\\R+", ","))) {
            int first = entry.indexOf(':');
            if (first < 1) throw new IllegalArgumentException("映射格式为 公网端口:目标端口 或 公网端口:目标地址:目标端口");
            int remote = parsePort(entry.substring(0, first), "公网端口");
            String destination = entry.substring(first + 1).trim();
            String host = "127.0.0.1", port = destination;
            if (destination.contains(":")) {
                int last = destination.lastIndexOf(':');
                host = destination.substring(0, last);
                port = destination.substring(last + 1);
                if (host.contains(":") && !(host.startsWith("[") && host.endsWith("]")))
                    throw new IllegalArgumentException("IPv6 目标地址需用方括号包围，例如 18080:[::1]:8080");
            }
            host = TargetAddress.validate(host);
            int local = parsePort(port, "本地目标端口");
            if (local == admin) throw new IllegalArgumentException("不能把管理端口 " + admin + " 作为转发目标");
            if (!seen.add(remote)) throw new IllegalArgumentException("公网端口重复: " + remote);
            result.add(new Mapping(remote, host, local));
        }
        if (result.size() > 4096) throw new IllegalArgumentException("最多可配置 4096 个映射");
        return List.copyOf(result);
    }

    private static Set<Integer> disabled(Properties p, Set<Integer> configured) {
        Set<Integer> disabled = new LinkedHashSet<>(PortRanges.parse(p.getProperty("disabled.forwards", ""), 0, 0));
        if (!configured.containsAll(disabled)) throw new IllegalArgumentException("disabled.forwards 只能包含已配置的公网端口");
        return Set.copyOf(disabled);
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
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException(key + " 不能为空");
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
