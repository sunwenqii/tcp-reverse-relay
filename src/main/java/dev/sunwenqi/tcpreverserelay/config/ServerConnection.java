package dev.sunwenqi.tcpreverserelay.config;

import java.util.Map;

/** One server endpoint shared by every mapping in a client instance. No DNS or network I/O in validation. */
public record ServerConnection(String host, int port) {
    public ServerConnection {
        if (host == null || host.isBlank() || host.contains(":")) throw invalidHost();
        try { host = TargetAddress.validate(host); }
        catch (IllegalArgumentException e) { throw invalidHost(); }
        AppSettings.parsePort(Integer.toString(port), "握手端口");
    }
    public void validateAdminPort(int adminPort) {
        if (port == adminPort && (host.equalsIgnoreCase("localhost") || host.startsWith("127.") && host.matches("[0-9.]+") || host.equals("0.0.0.0")))
            throw new IllegalArgumentException("服务器握手端口不能指向本机的管理端口");
    }
    public Map<String, String> properties() { return Map.of("server.host", host, "handshake.port", Integer.toString(port)); }
    private static IllegalArgumentException invalidHost() {
        return new IllegalArgumentException("服务器地址需为 IPv4 地址或域名，不要包含协议、路径或端口");
    }
}
